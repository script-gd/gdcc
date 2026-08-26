package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CCoroutineFrameContext;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.lir.insn.AwaitInsn;
import gd.script.gdcc.type.GdSignalType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdccCoroStateType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.List;

/// C code generator for `AWAIT` (contract: `gdcc_low_ir.md` §Coroutine Instructions,
/// `frontend_await_implementation.md` §7, ownership spec §3.10).
///
/// Dispatch is purely static-type-driven by the operand; no cross-instruction bookkeeping:
/// - `Signal` operand → `gdcc_coro_await_signal` (one-shot connect, resume value staged
///   through a Variant temp);
/// - `compiler::GdccCoroState` operand → `gdcc_coro_state_identify` + `gdcc_coro_await_state`
///   (typed `out_typed` channel; the OWNED state reference is handed to the helper and the
///   source slot is reset to moved-from `NULL` before the call);
/// - `Variant` operand → `gdcc_coro_await_dynamic` (runtime three-layer dispatch).
///
/// Every path ends with the cancel check: a cancel-resume returns from the runtime helper
/// WITHOUT writing the result channel, so the body jumps straight to `__finally__` (whose
/// `_return_val` default is already in place from `__prepare__`) before any result is read.
public final class AwaitInsnGen implements CInsnGen<AwaitInsn> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.AWAIT);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var insn = bodyBuilder.getCurrentInsn(this);
        if (!bodyBuilder.isCoroutineBody()) {
            throw bodyBuilder.invalidInsn("await is only valid inside an is_coroutine function");
        }
        var operandVar = requireVariable(bodyBuilder, insn.operandId(), "operand");
        var resultVar = requireVariable(bodyBuilder, insn.resultId(), "result");
        // The result is a frontend-published value type; the coroutine state reference itself
        // is only ever the operand, never the await result.
        InsnGenSupport.rejectCompilerOnlyVariable(bodyBuilder, resultVar, "await result");

        switch (operandVar.type()) {
            case GdSignalType _ -> emitSignalAwait(bodyBuilder, operandVar, resultVar);
            case GdccCoroStateType _ -> emitStateAwait(bodyBuilder, operandVar, resultVar);
            case GdVariantType _ -> emitDynamicAwait(bodyBuilder, insn, operandVar, resultVar);
            default -> throw bodyBuilder.invalidInsn(
                    "await operand must be Signal, Variant or compiler::GdccCoroState, got '"
                            + operandVar.type().getTypeName() + "'");
        }
    }

    /// Signal path: `gdcc_coro_await_signal(&$sig, &out, _co, &header)`. The helper
    /// raw-overwrites `out` (it never destroys a previous value), and on the signal-arity
    /// rules the produced value is always a Variant, so the result is staged through an
    /// uninitialized temp and merged into the published result slot (slot-write discipline:
    /// overwrite destroys the old value) only after the cancel check.
    private void emitSignalAwait(@NotNull CBodyBuilder bodyBuilder,
                                 @NotNull LirVariable operandVar,
                                 @NotNull LirVariable resultVar) {
        var signalArg = InsnGenSupport.renderArgumentCode(
                bodyBuilder, bodyBuilder.valueOfVar(operandVar), "await signal operand");
        var outTemp = bodyBuilder.newTempVariable("await_signal_out", GdVariantType.VARIANT);
        bodyBuilder.declareUninitializedTempVar(outTemp);
        bodyBuilder.appendLine("gdcc_coro_await_signal(" + signalArg + ", &" + outTemp.name() + ", "
                + CCoroutineFrameContext.CO_PARAM + ", " + CCoroutineFrameContext.selfHeaderExpr() + ");");
        emitCancelCheck(bodyBuilder);
        // Every non-cancel helper path (resume callback / connect-failure fallback) writes out.
        outTemp.setInitialized(true);
        materializeVariantResult(bodyBuilder, resultVar, outTemp, "await signal result target");
        bodyBuilder.destroyTempVar(outTemp);
    }

    /// Static coroutine-call path: identify the state header through the dedicated
    /// binding token, hand the call site's OWNED reference to `gdcc_coro_await_state` (which
    /// releases it internally, even on the done fast path), and leave the source slot in the
    /// moved-from `NULL` state before the call. If recognition fails, the slot remains owned
    /// so `__finally__` releases it instead of leaking it. The typed result slot is written by
    /// the state class descriptor's
    /// `copy_ret_slot`, which itself destroys/releases the old slot value on overwrite.
    private void emitStateAwait(@NotNull CBodyBuilder bodyBuilder,
                                @NotNull LirVariable operandVar,
                                @NotNull LirVariable resultVar) {
        if (bodyBuilder.isEffectivelyRef(operandVar)) {
            throw bodyBuilder.invalidInsn("await coroutine-state operand '" + operandVar.id()
                    + "' must not be a reference: the single-consumer OWNED slot is reset to NULL");
        }
        var stateSlot = bodyBuilder.valueOfVar(operandVar).generateCode();
        var resultAddress = renderResultSlotAddress(bodyBuilder, resultVar);
        // Unique-per-site header temp: identify must read the slot before it is reset, and the
        // await call must observe the reset slot (ownership transfer happens at the call).
        if (bodyBuilder.currentBlock() == null) {
            throw bodyBuilder.invalidInsn("await state must be inside a block");
        }
        var calleeTemp = "__gdcc_await_callee_" + bodyBuilder.currentBlock().id()
                + "_" + bodyBuilder.currentInsnIndex();
        bodyBuilder.appendLine("gdcc_coro_state_header *" + calleeTemp
                + " = gdcc_coro_state_identify(" + stateSlot + ");");
        bodyBuilder.appendLine("if (" + calleeTemp + " != NULL) {");
        bodyBuilder.appendLine("    " + stateSlot + " = NULL;");
        bodyBuilder.appendLine("}");
        bodyBuilder.appendLine("gdcc_coro_await_state(" + calleeTemp + ", " + resultAddress + ", "
                + CCoroutineFrameContext.CO_PARAM + ", " + CCoroutineFrameContext.selfHeaderExpr() + ");");
        emitCancelCheck(bodyBuilder);
    }

    /// Dynamic path: `gdcc_coro_await_dynamic(&operand, &out, _co, &header)`. The helper may
    /// destroy/reset the operand slot (own-state suspension cuts the callee edge), so a
    /// reference operand is rejected, and the result must not alias the operand (a self
    /// copy-construct into the same Variant slot would leak). The result is always Variant
    /// per the frontend contract; staging through a temp keeps overwrite discipline identical
    /// to the signal path.
    private void emitDynamicAwait(@NotNull CBodyBuilder bodyBuilder,
                                  @NotNull AwaitInsn insn,
                                  @NotNull LirVariable operandVar,
                                  @NotNull LirVariable resultVar) {
        if (bodyBuilder.isEffectivelyRef(operandVar)) {
            throw bodyBuilder.invalidInsn("await dynamic operand '" + operandVar.id()
                    + "' must not be a reference: the runtime helper consumes/resets the operand slot");
        }
        if (!(resultVar.type() instanceof GdVariantType)) {
            throw bodyBuilder.invalidInsn("await on a Variant operand always publishes a Variant result, got '"
                    + resultVar.type().getTypeName() + "'");
        }
        if (insn.resultId().equals(insn.operandId())) {
            throw bodyBuilder.invalidInsn("await dynamic result must not alias its operand '"
                    + insn.operandId() + "': the helper resets the operand slot");
        }
        var operandArg = InsnGenSupport.renderArgumentCode(
                bodyBuilder, bodyBuilder.valueOfVar(operandVar), "await dynamic operand");
        var outTemp = bodyBuilder.newTempVariable("await_dynamic_out", GdVariantType.VARIANT);
        bodyBuilder.declareUninitializedTempVar(outTemp);
        bodyBuilder.appendLine("gdcc_coro_await_dynamic(" + operandArg + ", &" + outTemp.name() + ", "
                + CCoroutineFrameContext.CO_PARAM + ", " + CCoroutineFrameContext.selfHeaderExpr() + ");");
        emitCancelCheck(bodyBuilder);
        outTemp.setInitialized(true);
        materializeVariantResult(bodyBuilder, resultVar, outTemp, "await dynamic result target");
        bodyBuilder.destroyTempVar(outTemp);
    }

    /// Merges the staged Variant resume value into the published result slot: direct copy for
    /// a Variant slot (constructor call assignment, mirroring the established Variant
    /// slot-write pattern), the existing unpack boundary for a typed slot.
    private void materializeVariantResult(@NotNull CBodyBuilder bodyBuilder,
                                          @NotNull LirVariable resultVar,
                                          @NotNull CBodyBuilder.TempVar outTemp,
                                          @NotNull String useSite) {
        if (resultVar.type() instanceof GdVariantType) {
            bodyBuilder.callAssign(
                    bodyBuilder.targetOfVar(resultVar),
                    "godot_new_Variant_with_Variant",
                    GdVariantType.VARIANT,
                    List.of(outTemp)
            );
            return;
        }
        InsnGenSupport.unpackVariantAssign(
                bodyBuilder, bodyBuilder.targetOfVar(resultVar), resultVar.type(), outTemp, useSite);
    }

    /// Cancel-resume poll frozen in ownership spec §3.10: on cancel the helper returned
    /// without writing the result channel, so the body abandons the instruction and runs the
    /// `__finally__` cleanup exactly once (the frame return slot then receives the
    /// `__prepare__` default `_return_val`).
    private void emitCancelCheck(@NotNull CBodyBuilder bodyBuilder) {
        bodyBuilder.appendLine("if (" + CCoroutineFrameContext.cancelFlagExpr() + ") {");
        bodyBuilder.appendLine("    goto __finally__;");
        bodyBuilder.appendLine("}");
    }

    /// Address of the awaiter's own typed result storage for `gdcc_coro_await_state`'s
    /// `void *out_typed` channel (locals `&$id`, coroutine frame parameters
    /// `&_coro_state->_coro_param_<id>`; both are owning writable storage).
    private @NotNull String renderResultSlotAddress(@NotNull CBodyBuilder bodyBuilder,
                                                    @NotNull LirVariable resultVar) {
        if (bodyBuilder.isEffectivelyRef(resultVar)) {
            throw bodyBuilder.invalidInsn("await result variable '" + resultVar.id()
                    + "' must not be a reference: the resume channel needs owning storage");
        }
        return "&" + bodyBuilder.valueOfVar(resultVar).generateCode();
    }

    private @NotNull LirVariable requireVariable(@NotNull CBodyBuilder bodyBuilder,
                                                 @NotNull String variableId,
                                                 @NotNull String role) {
        var variable = bodyBuilder.func().getVariableById(variableId);
        if (variable == null) {
            throw bodyBuilder.invalidInsn("await " + role + " variable ID '" + variableId
                    + "' not found in function");
        }
        return variable;
    }
}

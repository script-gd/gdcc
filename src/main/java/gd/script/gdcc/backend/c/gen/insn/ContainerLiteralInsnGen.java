package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.lir.insn.ConstructContainerLiteralInsn;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdDictionaryType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;

/// C generator for `construct_container_literal`.
///
/// Builds a fresh non-ref Array/Dictionary via empty `constructBuiltin`, then fills it by packing each
/// already-materialized operand into a generator-local Variant and calling
/// `godot_Array_push_back` / `godot_Dictionary_set`. Does not re-decide element conversions (frontend
/// boundary materialization owns that). See
/// `doc/module_impl/frontend/frontend_container_literal_implementation.md` §8.
public final class ContainerLiteralInsnGen implements CInsnGen<ConstructContainerLiteralInsn> {
    private static final String ARRAY_PUSH_BACK = "godot_Array_push_back";
    private static final String DICTIONARY_SET = "godot_Dictionary_set";

    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.CONSTRUCT_CONTAINER_LITERAL);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var instruction = bodyBuilder.getCurrentInsn(this);
        var resultVar = resolveResultVariable(bodyBuilder, instruction);
        var target = bodyBuilder.targetOfVar(resultVar);

        try {
            switch (resultVar.type()) {
                case GdArrayType _ -> emitArrayLiteral(bodyBuilder, instruction, resultVar, target);
                case GdDictionaryType _ -> emitDictionaryLiteral(bodyBuilder, instruction, resultVar, target);
                default -> throw bodyBuilder.invalidInsn(
                        "construct_container_literal result '"
                                + resultVar.id()
                                + "' must be non-ref Array or Dictionary, got '"
                                + resultVar.type().getTypeName()
                                + "'"
                );
            }
        } catch (IllegalArgumentException ex) {
            throw bodyBuilder.invalidInsn(ex.getMessage());
        }
    }

    /// Empty construct + ordered push_back; each pack temp is destroyed before the next element.
    private void emitArrayLiteral(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull ConstructContainerLiteralInsn instruction,
            @NotNull LirVariable resultVar,
            @NotNull CBodyBuilder.TargetRef target
    ) {
        bodyBuilder.helper().builtinBuilder().constructBuiltin(bodyBuilder, target, List.of());
        var containerValue = bodyBuilder.valueOfVar(resultVar);
        var operands = instruction.operands();
        for (var index = 0; index < operands.size(); index++) {
            var elementVar = resolveOperandVariable(bodyBuilder, operands.get(index), index);
            var elementOperand = InsnGenSupport.materializeVariantOperand(
                    bodyBuilder,
                    elementVar,
                    "clit_elem"
            );
            bodyBuilder.callVoid(ARRAY_PUSH_BACK, List.of(containerValue, elementOperand.variantValue()));
            // push_back returns void; destroy the carrier regardless of engine typed-write behavior.
            if (elementOperand.tempVar() != null) {
                bodyBuilder.destroyTempVar(elementOperand.tempVar());
            }
        }
    }

    /// Empty construct + even-count pair set; bool false is a runtime write failure with cleanup return.
    private void emitDictionaryLiteral(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull ConstructContainerLiteralInsn instruction,
            @NotNull LirVariable resultVar,
            @NotNull CBodyBuilder.TargetRef target
    ) {
        var operands = instruction.operands();
        if ((operands.size() & 1) != 0) {
            throw bodyBuilder.invalidInsn(
                    "construct_container_literal Dictionary requires even operand count (key/value pairs), got "
                            + operands.size()
            );
        }
        bodyBuilder.helper().builtinBuilder().constructBuiltin(bodyBuilder, target, List.of());
        var containerValue = bodyBuilder.valueOfVar(resultVar);
        for (var index = 0; index < operands.size(); index += 2) {
            var keyVar = resolveOperandVariable(bodyBuilder, operands.get(index), index);
            var valueVar = resolveOperandVariable(bodyBuilder, operands.get(index + 1), index + 1);
            var keyOperand = InsnGenSupport.materializeVariantOperand(bodyBuilder, keyVar, "clit_key");
            var valueOperand = InsnGenSupport.materializeVariantOperand(bodyBuilder, valueVar, "clit_val");
            var okFlag = bodyBuilder.newTempVariable("clit_dict_set_ok", GdBoolType.BOOL);
            bodyBuilder.declareTempVar(okFlag);
            bodyBuilder.callAssign(
                    okFlag,
                    DICTIONARY_SET,
                    GdBoolType.BOOL,
                    List.of(containerValue, keyOperand.variantValue(), valueOperand.variantValue())
            );
            // Destroy pack carriers unconditionally after set (codegen must not run failure-path
            // destroyTempVar first — that clears TempVar.initialized and skips success-path destroy).
            // Success: container owns the written values. Failure: set did not retain them.
            destroyOptionalTemps(bodyBuilder, valueOperand.tempVar(), keyOperand.tempVar());
            bodyBuilder.appendLine("if (!" + okFlag.name() + ") {");
            InsnGenSupport.emitRuntimeFailureReturn(
                    bodyBuilder,
                    "construct_container_literal dictionary set failed: result=$"
                            + resultVar.id()
                            + ", key=$"
                            + keyVar.id()
                            + ", value=$"
                            + valueVar.id()
            );
            bodyBuilder.appendLine("}");
        }
    }

    private static void destroyOptionalTemps(
            @NotNull CBodyBuilder bodyBuilder,
            @Nullable CBodyBuilder.TempVar valueTemp,
            @Nullable CBodyBuilder.TempVar keyTemp
    ) {
        if (valueTemp != null && keyTemp != null) {
            InsnGenSupport.destroyInitializedTemps(bodyBuilder, valueTemp, keyTemp);
        } else if (valueTemp != null) {
            InsnGenSupport.destroyInitializedTemps(bodyBuilder, valueTemp);
        } else if (keyTemp != null) {
            InsnGenSupport.destroyInitializedTemps(bodyBuilder, keyTemp);
        }
    }

    private @NotNull LirVariable resolveResultVariable(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull ConstructContainerLiteralInsn instruction
    ) {
        var resultId = instruction.resultId();
        if (resultId == null) {
            throw bodyBuilder.invalidInsn("construct_container_literal missing result variable ID");
        }
        var resultVar = bodyBuilder.func().getVariableById(resultId);
        if (resultVar == null) {
            throw bodyBuilder.invalidInsn("Result variable ID '" + resultId + "' does not exist");
        }
        if (resultVar.ref()) {
            throw bodyBuilder.invalidInsn(
                    "construct_container_literal result variable ID '" + resultId + "' cannot be a reference"
            );
        }
        return resultVar;
    }

    private @NotNull LirVariable resolveOperandVariable(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull LirInstruction.Operand operand,
            int index
    ) {
        if (!(operand instanceof LirInstruction.VariableOperand(var variableId))) {
            // Record construction already rejects non-variable operands; keep fail-closed for hand-built IR.
            throw bodyBuilder.invalidInsn(
                    "construct_container_literal operand[" + index + "] must be a variable operand"
            );
        }
        var variable = bodyBuilder.func().getVariableById(variableId);
        if (variable == null) {
            throw bodyBuilder.invalidInsn(
                    "construct_container_literal operand[" + index + "] variable ID '" + variableId + "' not found"
            );
        }
        InsnGenSupport.rejectCompilerOnlyVariable(bodyBuilder, variable, "construct_container_literal operand");
        return variable;
    }
}

package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.lir.insn.CallStaticMethodInsn;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;

/// C code generator for `CALL_STATIC_METHOD` (`frontend_await_implementation.md`).
///
/// Static calls have no receiver and no dynamic fallback: resolution always goes through
/// `BackendMethodCallResolver.resolveStatic` (receiver canonical name -> `ScopeTypeMeta` ->
/// `ScopeMethodResolver.resolveStaticMethod`), which can only produce GDCC/ENGINE/BUILTIN exact
/// routes. The fixed-argument/default-completion/vararg/result plumbing is shared with
/// `CallMethodInsnGen` with a null receiver; coroutine callees target the same start thunk ABI
/// minus the receiver parameter.
public final class CallStaticMethodInsnGen implements CInsnGen<CallStaticMethodInsn> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.CALL_STATIC_METHOD);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var instruction = bodyBuilder.getCurrentInsn(this);
        var argVars = CallMethodInsnGen.resolveArgumentVariables(
                bodyBuilder,
                instruction.methodName(),
                instruction.args(),
                "call_static_method"
        );
        var resolved = BackendMethodCallResolver.resolveStatic(
                bodyBuilder,
                instruction.className(),
                instruction.methodName(),
                argVars
        );
        if (resolved.coroutine()) {
            CallMethodInsnGen.emitCoroutineStartCall(
                    bodyBuilder, instruction.resultId(), null, argVars, resolved, "CALL_STATIC_METHOD");
            return;
        }
        CallMethodInsnGen.emitResolvedCall(
                bodyBuilder, instruction.resultId(), null, argVars, resolved, "CALL_STATIC_METHOD");
    }
}

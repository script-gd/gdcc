package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.lir.insn.AssertInsn;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdStringType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;

/// Lowers the user-level `assert` instruction to the `gdcc_assert_failed` guard.
///
/// All IR contract violations are validated here before any C is emitted:
/// - the condition variable must exist and already be `bool` (truthiness normalization is a
///   frontend lowering responsibility, see `gdcc_low_ir.md` §Misc Instructions)
/// - the optional message variable must exist and be directly `String`-assignable under the
///   backend `ClassRegistry.checkAssignable` rule
/// - the instruction must not appear inside `__finally__` (enforced by the builder guard)
///
/// A textual `$r = assert ...;` result prefix is silently discarded at LIR parse time,
/// so there is deliberately no result-slot validation here.
public final class AssertInsnGen implements CInsnGen<AssertInsn> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.ASSERT);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var insn = bodyBuilder.getCurrentInsn(this);

        var conditionVariable = bodyBuilder.func().getVariableById(insn.conditionId());
        if (conditionVariable == null) {
            throw bodyBuilder.invalidInsn("assert condition variable not found: " + insn.conditionId());
        }
        if (!(conditionVariable.type() instanceof GdBoolType)) {
            throw bodyBuilder.invalidInsn("assert condition '" + insn.conditionId() + "' must be bool, got '"
                    + conditionVariable.type().getTypeName() + "'");
        }

        LirVariable messageVariable = null;
        if (insn.messageId() != null) {
            messageVariable = bodyBuilder.func().getVariableById(insn.messageId());
            if (messageVariable == null) {
                throw bodyBuilder.invalidInsn("assert message variable not found: " + insn.messageId());
            }
            if (!bodyBuilder.classRegistry().checkAssignable(messageVariable.type(), GdStringType.STRING)) {
                throw bodyBuilder.invalidInsn("assert message '" + insn.messageId()
                        + "' must be assignable to String, got '" + messageVariable.type().getTypeName() + "'");
            }
        }

        bodyBuilder.emitAssertGuard(conditionVariable, messageVariable);
    }
}

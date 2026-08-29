package gd.script.gdcc.lir.insn;

import gd.script.gdcc.enums.GdInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// User-level condition assertion (`gdcc_low_ir.md` §Misc Instructions).
///
/// Contract summary:
/// - No result (`ReturnKind.NONE`). A textual `$r = assert $c;` prefix is accepted by the
///   generic parser and silently discarded here; `resultId()` is always `null`.
/// - `conditionId` is required and must already be `bool`. Truthiness normalization is
///   frontend lowering's job; this instruction does not booleanize other types.
/// - `messageId` is optional. When present it must be assignable to `String`.
/// - Not a lifecycle instruction: no provenance, no retain/release/destroy.
/// - Not a terminator: it sits in the ordinary instruction region of a basic block.
public record AssertInsn(
        @NotNull String conditionId,
        @Nullable String messageId
) implements MiscInstruction {

    public AssertInsn {
        Objects.requireNonNull(conditionId, "conditionId must not be null");
    }

    @Override
    public String resultId() {
        return null;
    }

    @Override
    public GdInstruction opcode() {
        return GdInstruction.ASSERT;
    }

    @Override
    public @NotNull List<Operand> operands() {
        if (messageId == null) {
            return List.of(new VariableOperand(conditionId));
        }
        return List.of(
                new VariableOperand(conditionId),
                new VariableOperand(messageId)
        );
    }
}

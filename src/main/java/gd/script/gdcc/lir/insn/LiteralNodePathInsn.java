package gd.script.gdcc.lir.insn;

import gd.script.gdcc.enums.GdInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// `value` stores the normalized runtime payload, not the raw `^"..."` source lexeme.
///
/// Producers must strip the `NodePath` literal syntax and decode escapes before publishing this
/// instruction. The backend deliberately performs no raw-lexeme shape rejection: a decoded payload
/// may legitimately look like a lexeme (e.g. `^"foo"` decoded from `^"^\"foo\""`).
public record LiteralNodePathInsn(@Nullable String resultId, @NotNull String value) implements NewDataInstruction {

    @Override
    public GdInstruction opcode() {
        return GdInstruction.LITERAL_NODE_PATH;
    }

    @Override
    public @NotNull List<Operand> operands() {
        return List.of(new StringOperand(value));
    }
}

package dev.superice.gdcc.lir.insn;

import dev.superice.gdcc.enums.GdInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// `value` stores the normalized runtime payload, not the raw source lexeme.
///
/// Producers must strip the outer quotes and decode escapes before publishing this instruction.
public record LiteralStringInsn(@Nullable String resultId, @NotNull String value) implements NewDataInstruction {

    @Override
    public GdInstruction opcode() {
        return GdInstruction.LITERAL_STRING;
    }

    @Override
    public @NotNull List<Operand> operands() {
        return List.of(new StringOperand(value));
    }
}


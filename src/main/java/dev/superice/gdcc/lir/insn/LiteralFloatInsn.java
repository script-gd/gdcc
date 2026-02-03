package dev.superice.gdcc.lir.insn;

import dev.superice.gdcc.enums.GdInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record LiteralFloatInsn(@Nullable String resultId, double value) implements NewDataInstruction {

    @Override
    public GdInstruction opcode() {
        return GdInstruction.LITERAL_FLOAT;
    }

    @Override
    public @NotNull List<Operand> operands() {
        return List.of(new FloatOperand(value));
    }
}


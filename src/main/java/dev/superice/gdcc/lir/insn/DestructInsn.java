package dev.superice.gdcc.lir.insn;

import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.lir.LirInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record DestructInsn(@NotNull String variableId) implements ConstructionInstruction {
    @Override
    public @Nullable String resultId() {
        return null;
    }

    @Override
    public GdInstruction opcode() {
        return GdInstruction.DESTRUCT;
    }

    @Override
    public @NotNull List<Operand> operands() {
        return List.of(new VariableOperand(variableId));
    }
}


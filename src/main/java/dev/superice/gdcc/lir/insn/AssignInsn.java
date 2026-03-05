package dev.superice.gdcc.lir.insn;

import dev.superice.gdcc.enums.GdInstruction;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public record AssignInsn(@NotNull String resultId, @NotNull String sourceId) implements LoadStoreInstruction {

    @Override
    public GdInstruction opcode() {
        return GdInstruction.ASSIGN;
    }

    @Override
    public @NotNull List<Operand> operands() {
        return List.of(new VariableOperand(sourceId));
    }
}


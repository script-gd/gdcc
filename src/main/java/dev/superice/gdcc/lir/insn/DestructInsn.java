package dev.superice.gdcc.lir.insn;

import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.enums.LifecycleProvenance;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

public record DestructInsn(@NotNull String variableId,
                           @NotNull LifecycleProvenance provenance) implements LifecycleInstruction {
    public DestructInsn(@NotNull String variableId) {
        this(variableId, LifecycleProvenance.UNKNOWN);
    }

    public DestructInsn {
        Objects.requireNonNull(variableId, "variableId");
        Objects.requireNonNull(provenance, "provenance");
    }

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

    @Override
    public @NotNull LifecycleProvenance getProvenance() {
        return provenance;
    }
}

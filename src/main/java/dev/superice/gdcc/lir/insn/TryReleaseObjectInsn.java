package dev.superice.gdcc.lir.insn;

import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.enums.LifecycleProvenance;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

public record TryReleaseObjectInsn(@NotNull String objectId,
                                   @NotNull LifecycleProvenance provenance) implements LifecycleInstruction {
    public TryReleaseObjectInsn(@NotNull String objectId) {
        this(objectId, LifecycleProvenance.UNKNOWN);
    }

    public TryReleaseObjectInsn {
        Objects.requireNonNull(objectId, "objectId");
        Objects.requireNonNull(provenance, "provenance");
    }

    @Override
    @Nullable public String resultId() {
        return null;
    }

    @Override
    public GdInstruction opcode() {
        return GdInstruction.TRY_RELEASE_OBJECT;
    }

    @Override
    public @NotNull List<Operand> operands() {
        return List.of(new VariableOperand(objectId));
    }

    @Override
    public @NotNull LifecycleProvenance getProvenance() {
        return provenance;
    }
}

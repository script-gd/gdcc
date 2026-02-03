package dev.superice.gdcc.lir.insn;

import dev.superice.gdcc.enums.GdInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record GetVariantTypeInsn(@Nullable String resultId, @NotNull String variantId) implements TypeInstruction {

    @Override
    public GdInstruction opcode() {
        return GdInstruction.GET_VARIANT_TYPE;
    }

    @Override
    public @NotNull List<Operand> operands() {
        return List.of(new VariableOperand(variantId));
    }
}

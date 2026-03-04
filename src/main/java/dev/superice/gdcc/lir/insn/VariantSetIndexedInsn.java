package dev.superice.gdcc.lir.insn;

import dev.superice.gdcc.enums.GdInstruction;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public record VariantSetIndexedInsn(@NotNull String variantId, @NotNull String indexId,
                                    @NotNull String valueId) implements IndexingInstruction {

    @Override
    public String resultId() {
        return null;
    }

    @Override
    public GdInstruction opcode() {
        return GdInstruction.VARIANT_SET_INDEXED;
    }

    @Override
    public @NotNull List<Operand> operands() {
        return List.of(new VariableOperand(variantId), new VariableOperand(indexId), new VariableOperand(valueId));
    }
}


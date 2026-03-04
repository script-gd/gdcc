package dev.superice.gdcc.lir.insn;

import dev.superice.gdcc.enums.GdInstruction;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public record VariantSetNamedInsn(@NotNull String namedVariantId, @NotNull String nameId,
                                  @NotNull String valueId) implements IndexingInstruction {

    @Override
    public String resultId() {
        return null;
    }

    @Override
    public GdInstruction opcode() {
        return GdInstruction.VARIANT_SET_NAMED;
    }

    @Override
    public @NotNull List<Operand> operands() {
        return List.of(new VariableOperand(namedVariantId), new VariableOperand(nameId), new VariableOperand(valueId));
    }
}


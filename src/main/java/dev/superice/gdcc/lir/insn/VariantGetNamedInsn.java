package dev.superice.gdcc.lir.insn;

import dev.superice.gdcc.enums.GdInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record VariantGetNamedInsn(@Nullable String resultId, @NotNull String namedVariantId,
                                  @NotNull String nameId) implements IndexingInstruction {

    @Override
    public GdInstruction opcode() {
        return GdInstruction.VARIANT_GET_NAMED;
    }

    @Override
    public @NotNull List<Operand> operands() {
        return List.of(new VariableOperand(namedVariantId), new VariableOperand(nameId));
    }
}


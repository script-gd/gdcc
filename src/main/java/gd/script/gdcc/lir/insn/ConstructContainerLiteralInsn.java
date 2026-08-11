package gd.script.gdcc.lir.insn;

import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.lir.LirInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Populates a fresh Array or Dictionary from already-materialized element/key/value slots.
///
/// Text form: `$result = construct_container_literal $op0 $op1 ...`
/// Family is decided solely by the result variable type (`GdArrayType` vs `GdDictionaryType`):
/// - Array: operands are element0..elementN
/// - Dictionary: operands are key0/value0/key1/value1/... (count must be even at backend)
///
/// Empty operands are legal. Does not replace frozen empty `construct_array` / `construct_dictionary`.
public record ConstructContainerLiteralInsn(
        @Nullable String resultId,
        @NotNull List<Operand> operands
) implements ConstructionInstruction {

    public ConstructContainerLiteralInsn {
        operands = List.copyOf(Objects.requireNonNull(operands, "operands must not be null"));
        for (var index = 0; index < operands.size(); index++) {
            var operand = operands.get(index);
            if (!(operand instanceof LirInstruction.VariableOperand)) {
                throw new IllegalArgumentException(
                        "construct_container_literal operand[" + index + "] must be VariableOperand, got "
                                + operand.getClass().getSimpleName()
                );
            }
        }
    }

    @Override
    public GdInstruction opcode() {
        return GdInstruction.CONSTRUCT_CONTAINER_LITERAL;
    }

    @Override
    public @NotNull List<Operand> operands() {
        return operands;
    }
}

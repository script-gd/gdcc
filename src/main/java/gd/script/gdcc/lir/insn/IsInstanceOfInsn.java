package gd.script.gdcc.lir.insn;

import gd.script.gdcc.enums.GdInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Unified type-test LIR instruction (GDScript `is` / `is not`).
///
/// Represents `$result = is_instance_of "<type_name>" $value`. [typeName] is the full compile-time
/// type text (builtin name, canonical object class, or parameterized container like `"Array[int]"`);
/// [valueId] is the ordinary typed value being tested (not forced to Variant).
/// Backend dispatches by value static type + type name; see the frontend type-test implementation
/// contract.
public record IsInstanceOfInsn(@Nullable String resultId, @NotNull String typeName,
                               @NotNull String valueId) implements TypeInstruction {
    public IsInstanceOfInsn {
        Objects.requireNonNull(typeName, "typeName must not be null");
        Objects.requireNonNull(valueId, "valueId must not be null");
    }

    @Override
    public GdInstruction opcode() {
        return GdInstruction.IS_INSTANCE_OF;
    }

    @Override
    public @NotNull List<Operand> operands() {
        return List.of(new StringOperand(typeName), new VariableOperand(valueId));
    }
}

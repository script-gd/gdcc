package gd.script.gdcc.lir.insn;

import gd.script.gdcc.enums.GdInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Unified type-test LIR instruction (GDScript `is` / `is not`).
///
/// Represents `$result = is_instance_of "<type_name>" $value`. The [className] field carries the
/// full compile-time type text (builtin name, canonical object class, or parameterized container
/// like `"Array[int]"`); [objectId] is the ordinary typed value being tested (not forced to Variant).
/// Backend dispatches by value static type + type name; see the implementation plan §3.3.
/// Field rename (`className`→`typeName`, `objectId`→`valueId`) is scheduled for Phase 2.
public record IsInstanceOfInsn(@Nullable String resultId, @NotNull String className,
                               @NotNull String objectId) implements TypeInstruction {
    public IsInstanceOfInsn {
        Objects.requireNonNull(className, "className (type name) must not be null");
        Objects.requireNonNull(objectId, "objectId (value id) must not be null");
    }

    @Override
    public GdInstruction opcode() {
        return GdInstruction.IS_INSTANCE_OF;
    }

    @Override
    public @NotNull List<Operand> operands() {
        return List.of(new StringOperand(className), new VariableOperand(objectId));
    }
}


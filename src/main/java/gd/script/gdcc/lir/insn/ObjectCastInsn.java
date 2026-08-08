package gd.script.gdcc.lir.insn;

import gd.script.gdcc.enums.GdInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Runtime object class cast for GDScript `as` (Object / Variant / Nil source → object target).
///
/// Text form: `$result = object_cast "<class_name>" $value` (opcode text unchanged).
/// @param className must be the canonical / Godot-facing runtime name; parser stores it as opaque text.
/// @param valueId is the source operand (source may be Object, Variant, or Nil).
/// @param resultId is optional: null means validated no-op at the backend (no runtime cast).
public record ObjectCastInsn(
        @Nullable String resultId,
        @NotNull String className,
        @NotNull String valueId
) implements TypeInstruction {
    public ObjectCastInsn {
        Objects.requireNonNull(className, "className must not be null");
        Objects.requireNonNull(valueId, "valueId must not be null");
    }

    @Override
    public GdInstruction opcode() {
        return GdInstruction.OBJECT_CAST;
    }

    @Override
    public @NotNull List<Operand> operands() {
        return List.of(new StringOperand(className), new VariableOperand(valueId));
    }
}

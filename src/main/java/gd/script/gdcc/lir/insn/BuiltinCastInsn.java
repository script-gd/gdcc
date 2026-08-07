package gd.script.gdcc.lir.insn;

import gd.script.gdcc.enums.GdInstruction;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Runtime builtin conversion for GDScript `as` (non-Object / non-Variant target).
///
/// Text form: `$result = builtin_cast "<target_type_name>" $value`.
/// @param targetTypeName  is opaque compile-time type text from `GdType.getTypeName()`
/// (e.g. `"int"`, `"Array[int]"`, `"Dictionary[String, int]"`); the parser does not re-resolve it.
/// @param resultId is required. Exact/identity and Variant-target casts use other insns, not this one.
public record BuiltinCastInsn(
        @NotNull String resultId,
        @NotNull String targetTypeName,
        @NotNull String valueId
) implements TypeInstruction {
    public BuiltinCastInsn {
        Objects.requireNonNull(resultId, "resultId must not be null");
        Objects.requireNonNull(targetTypeName, "targetTypeName must not be null");
        Objects.requireNonNull(valueId, "valueId must not be null");
    }

    @Override
    public GdInstruction opcode() {
        return GdInstruction.BUILTIN_CAST;
    }

    @Override
    public @NotNull List<Operand> operands() {
        return List.of(new StringOperand(targetTypeName), new VariableOperand(valueId));
    }
}

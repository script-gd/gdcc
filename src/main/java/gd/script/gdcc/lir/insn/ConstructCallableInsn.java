package gd.script.gdcc.lir.insn;

import gd.script.gdcc.enums.GdInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// Materializes a `godot_Callable` from an instance receiver and a compile-time method name.
///
/// Object/self receivers use `godot_new_Callable_with_Object_StringName`. Non-Object builtin
/// receivers use `godot_Callable_create` after an in-generator temporary Variant pack.
/// Static and utility references never produce this instruction.
public record ConstructCallableInsn(@Nullable String resultId,
                                    @NotNull String receiverVarId,
                                    @NotNull String methodName) implements ConstructionInstruction {

    @Override
    public GdInstruction opcode() {
        return GdInstruction.CONSTRUCT_CALLABLE;
    }

    @Override
    public @NotNull List<Operand> operands() {
        return List.of(new VariableOperand(receiverVarId), new StringOperand(methodName));
    }
}

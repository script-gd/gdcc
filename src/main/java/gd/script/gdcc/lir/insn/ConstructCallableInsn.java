package gd.script.gdcc.lir.insn;

import gd.script.gdcc.enums.GdInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// Materializes a `godot_Callable` from a live object receiver and a compile-time method name.
///
/// Bare `_handler` reads use a fixed `self` receiver. Receiver-qualified `obj._handler` /
/// `self._handler` reads pass the already-materialized object slot. The result is a destroyable
/// builtin value, not an object fat pointer, and does not keep the receiver alive.
///
/// Only Object/self receivers are legal. Builtin, static, and utility method-references never
/// produce this instruction.
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

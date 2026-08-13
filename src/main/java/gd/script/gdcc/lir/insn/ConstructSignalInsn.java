package gd.script.gdcc.lir.insn;

import gd.script.gdcc.enums.GdInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// Materializes a `godot_Signal` from a live object receiver and a compile-time signal name.
///
/// Bare `foo` reads use a fixed `self` receiver. Receiver-qualified `obj.foo` / `self.foo` reads
/// pass the already-materialized object slot. The result is a destroyable builtin value, not an
/// object fat pointer, and does not keep the receiver alive.
public record ConstructSignalInsn(@Nullable String resultId,
                                  @NotNull String receiverVarId,
                                  @NotNull String signalName) implements ConstructionInstruction {

    @Override
    public GdInstruction opcode() {
        return GdInstruction.CONSTRUCT_SIGNAL;
    }

    @Override
    public @NotNull List<Operand> operands() {
        return List.of(new VariableOperand(receiverVarId), new StringOperand(signalName));
    }
}

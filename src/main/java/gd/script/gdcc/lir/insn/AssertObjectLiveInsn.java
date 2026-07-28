package gd.script.gdcc.lir.insn;

import gd.script.gdcc.enums.GdInstruction;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/// Hard-fail guard before dereferencing an object value.
/// It has no result, does not retain/release/destroy, and needs no lifecycle provenance.
/// Null/freed objects enter the current function's stable runtime-error/default-return cleanup.
public record AssertObjectLiveInsn(@NotNull String objectId) implements MiscInstruction {

    @Override
    public String resultId() {
        return null;
    }

    @Override
    public GdInstruction opcode() {
        return GdInstruction.ASSERT_OBJECT_LIVE;
    }

    @Override
    public @NotNull List<Operand> operands() {
        return List.of(new VariableOperand(objectId));
    }
}

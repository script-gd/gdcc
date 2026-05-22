package gd.script.gdcc.lir.insn;

import gd.script.gdcc.enums.GdInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record LiteralIntInsn(@Nullable String resultId, long value) implements NewDataInstruction {

    @Override
    public GdInstruction opcode() {
        return GdInstruction.LITERAL_INT;
    }

    @Override
    public @NotNull List<Operand> operands() {
        return List.of(new IntOperand(value));
    }
}

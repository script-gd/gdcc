package dev.superice.gdcc.lir.insn;

import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.lir.LirInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public record CallGlobalInsn(@Nullable String resultId, @NotNull String functionName,
                             @NotNull List<Operand> args) implements CallInstruction {
    public CallGlobalInsn(@NotNull String functionName, @NotNull List<Operand> args) {
        this(null, functionName, args);
    }

    @Override
    public GdInstruction opcode() {
        return GdInstruction.CALL_GLOBAL;
    }

    @Override
    public @NotNull List<Operand> operands() {
        List<Operand> out = new ArrayList<>();
        out.add(new StringOperand(functionName));
        out.addAll(args);
        return out;
    }
}


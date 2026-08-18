package gd.script.gdcc.lir.insn;

import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record ConstructLambdaInsn(@Nullable String resultId, @NotNull String lambdaName,
                                  @NotNull List<Operand> captures) implements ConstructionInstruction {

    public ConstructLambdaInsn {
        lambdaName = StringUtil.requireNonBlank(lambdaName, "lambdaName");
        captures = List.copyOf(Objects.requireNonNull(captures, "captures must not be null"));
    }

    @Override
    public GdInstruction opcode() {
        return GdInstruction.CONSTRUCT_LAMBDA;
    }

    @Override
    public @NotNull List<Operand> operands() {
        List<Operand> out = new ArrayList<>();
        out.add(new StringOperand(lambdaName));
        out.addAll(captures);
        return out;
    }
}


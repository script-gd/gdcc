package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.lir.insn.AssignInsn;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Objects;

public final class AssignInsnGen implements CInsnGen<AssignInsn> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.ASSIGN);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var insn = bodyBuilder.getCurrentInsn(this);
        var resultId = insn.resultId();

        var func = bodyBuilder.func();
        var resultVar = func.getVariableById(Objects.requireNonNull(resultId));
        if (resultVar == null) {
            throw bodyBuilder.invalidInsn("Result variable ID " + resultId + " does not exist");
        }
        var sourceVar = func.getVariableById(insn.sourceId());
        if (sourceVar == null) {
            throw bodyBuilder.invalidInsn("Source variable ID " + insn.sourceId() + " does not exist");
        }

        var target = bodyBuilder.targetOfVar(resultVar);
        bodyBuilder.assignVar(target, bodyBuilder.valueOfVar(sourceVar));
    }
}


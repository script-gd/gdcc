package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.lir.insn.CallIntrinsicInsn;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumSet;

/// C code generator for backend-owned `call_intrinsic` instructions.
///
/// The generator owns the common LIR plumbing: white-list lookup plus result/argument slot
/// resolution. Each intrinsic implementation keeps its own narrow signature checks.
public final class CallIntrinsicInsnGen implements CInsnGen<CallIntrinsicInsn> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.CALL_INTRINSIC);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var insn = bodyBuilder.getCurrentInsn(this);
        var intrinsic = bodyBuilder.helper().intrinsicManager().find(insn.intrinsicName());
        if (intrinsic == null) {
            throw bodyBuilder.invalidInsn("Intrinsic function '" + insn.intrinsicName() + "' not found in registry");
        }

        var func = bodyBuilder.func();
        var resultId = insn.resultId();
        LirVariable resultVar = null;
        if (resultId != null) {
            resultVar = func.getVariableById(resultId);
            if (resultVar == null) {
                throw bodyBuilder.invalidInsn("Result variable ID '" + resultId + "' not found in function");
            }
        }

        var operands = insn.args();
        var argVars = new ArrayList<LirVariable>(operands.size());
        for (var i = 0; i < operands.size(); i++) {
            var operand = operands.get(i);
            if (!(operand instanceof LirInstruction.VariableOperand(var argId))) {
                throw bodyBuilder.invalidInsn("Argument #" + (i + 1) + " of intrinsic '" +
                        insn.intrinsicName() + "' must be a variable operand");
            }

            var argVar = func.getVariableById(argId);
            if (argVar == null) {
                throw bodyBuilder.invalidInsn("Argument variable ID '" + argId +
                        "' not found in function for intrinsic '" + insn.intrinsicName() + "'");
            }
            argVars.add(argVar);
        }

        intrinsic.generateCCode(bodyBuilder, resultVar, argVars);
    }
}

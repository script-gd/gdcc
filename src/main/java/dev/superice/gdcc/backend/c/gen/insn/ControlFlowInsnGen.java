package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.lir.insn.ControlFlowInstruction;
import dev.superice.gdcc.lir.insn.GoIfInsn;
import dev.superice.gdcc.lir.insn.GotoInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;

public final class ControlFlowInsnGen implements CInsnGen<ControlFlowInstruction> {
    private static final String RETURN_SLOT_ID = "_return_val";

    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.GOTO, GdInstruction.GO_IF, GdInstruction.RETURN);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var instruction = bodyBuilder.getCurrentInsn(this);
        var func = bodyBuilder.func();
        switch (instruction) {
            case GotoInsn gotoInsn -> {
                if (func.getBasicBlock(gotoInsn.targetBbId()) == null) {
                    throw bodyBuilder.invalidInsn("Invalid target basic block ID " + gotoInsn.targetBbId());
                }
                bodyBuilder.jump(gotoInsn.targetBbId());
            }
            case GoIfInsn goIfInsn -> {
                if (func.getBasicBlock(goIfInsn.trueBbId()) == null) {
                    throw bodyBuilder.invalidInsn("Invalid true target basic block ID " + goIfInsn.trueBbId());
                }
                if (func.getBasicBlock(goIfInsn.falseBbId()) == null) {
                    throw bodyBuilder.invalidInsn("Invalid false target basic block ID " + goIfInsn.falseBbId());
                }
                var variable = func.getVariableById(goIfInsn.conditionVarId());
                if (variable == null) {
                    throw bodyBuilder.invalidInsn("Condition variable ID " + goIfInsn.conditionVarId() + " does not exist");
                }
                if (!(variable.type() instanceof GdBoolType)) {
                    throw bodyBuilder.invalidInsn("Condition variable ID " + goIfInsn.conditionVarId() + " is not of bool type");
                }
                bodyBuilder.jumpIf(bodyBuilder.valueOfVar(variable), goIfInsn.trueBbId(), goIfInsn.falseBbId());
            }
            case ReturnInsn returnInsn -> {
                var returnValueId = returnInsn.returnValueId();
                if (func.getReturnType() instanceof GdVoidType) {
                    if (returnValueId != null) {
                        throw bodyBuilder.invalidInsn("Cannot return a value from void function");
                    }
                    bodyBuilder.returnVoid();
                } else {
                    if (returnValueId == null) {
                        throw bodyBuilder.invalidInsn("Return instruction missing return value for non-void function");
                    }
                    if (RETURN_SLOT_ID.equals(returnValueId)) {
                        if (bodyBuilder.checkInFinallyBlock()) {
                            bodyBuilder.returnTerminal();
                            return;
                        }
                    }
                    var variable = func.getVariableById(returnValueId);
                    if (variable == null) {
                        throw bodyBuilder.invalidInsn("Return value variable ID " + returnValueId + " does not exist");
                    }
                    bodyBuilder.returnValue(bodyBuilder.valueOfVar(variable));
                }
            }
            default -> throw bodyBuilder.invalidInsn(
                    "Unsupported control flow instruction type: " + instruction.getClass().getSimpleName());
        }
    }
}

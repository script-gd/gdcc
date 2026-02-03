package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CGenHelper;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.insn.ControlFlowInstruction;
import dev.superice.gdcc.lir.insn.GoIfInsn;
import dev.superice.gdcc.lir.insn.GotoInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Objects;

public final class ControlFlowInsnGen extends TemplateInsnGen<ControlFlowInstruction> {
    @Override
    protected @NotNull String getTemplatePath() {
        return "insn/control_flow.ftl";
    }

    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.GOTO, GdInstruction.GO_IF, GdInstruction.RETURN);
    }

    @Override
    protected void validateInstruction(@NotNull CGenHelper helper, @NotNull LirClassDef clazz, @NotNull LirFunctionDef func, @NotNull LirBasicBlock block, int insnIndex, @NotNull ControlFlowInstruction instruction) {
        switch (instruction) {
            case GotoInsn gotoInsn -> {
                if (func.getBasicBlock(gotoInsn.targetBbId()) == null) {
                    throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                            "Invalid target basic block ID " + gotoInsn.targetBbId());
                }
            }
            case GoIfInsn goIfInsn -> {
                if (func.getBasicBlock(goIfInsn.trueBbId()) == null) {
                    throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                            "Invalid true target basic block ID " + goIfInsn.trueBbId());
                }
                if (func.getBasicBlock(goIfInsn.falseBbId()) == null) {
                    throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                            "Invalid false target basic block ID " + goIfInsn.falseBbId());
                }
                var variable = func.getVariableById(goIfInsn.conditionVarId());
                if (variable == null) {
                    throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                            "Condition variable ID " + goIfInsn.conditionVarId() + " does not exist");
                }
                if (!(variable.type() instanceof GdBoolType)) {
                    throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                            "Condition variable ID " + goIfInsn.conditionVarId() + " is not of bool type");
                }
            }
            case ReturnInsn returnInsn -> {
                if (returnInsn.returnValueId() == null && !(func.getReturnType() instanceof GdVoidType)) {
                    throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                            "Return instruction missing return value for non-void function");
                }
                if (!(func.getReturnType() instanceof GdVoidType)) {
                    var variable = func.getVariableById(Objects.requireNonNull(returnInsn.returnValueId()));
                    if (variable == null) {
                        throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                                "Return value variable ID " + returnInsn.returnValueId() + " does not exist");
                    }
                }
            }
            default -> throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                    "Unsupported control flow instruction type: " + instruction.getClass().getSimpleName());
        }
    }
}

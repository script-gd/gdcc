package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CGenHelper;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.insn.CallGlobalInsn;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;

public final class CallGlobalInsnGen extends TemplateInsnGen<CallGlobalInsn> {
    @Override
    protected @NotNull String getTemplatePath() {
        return "insn/call_global.ftl";
    }

    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.CALL_GLOBAL);
    }

    @Override
    protected Map<String, Object> validateInstruction(@NotNull CGenHelper helper,
                                                      @NotNull LirClassDef clazz,
                                                      @NotNull LirFunctionDef func,
                                                      @NotNull LirBasicBlock block,
                                                      int insnIndex,
                                                      @NotNull CallGlobalInsn instruction) {
        var registry = helper.context().classRegistry();
        var functionDef = registry.findUtilityFunctionSignature(instruction.functionName());
        if (functionDef == null) {
            throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                    "Global function '" + instruction.functionName() + "' not found in registry");
        }
        var returnType = functionDef.returnType();
        if ((returnType == null || returnType instanceof GdVoidType) && instruction.resultId() != null) {
            throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                    "Function '" + instruction.functionName() + "' has no return value, but instruction " +
                            "specifies a result variable ID");
        }
        Objects.requireNonNull(returnType);
        if (instruction.resultId() == null) {
            throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                    "Function '" + instruction.functionName() + "' has a return value of type '" +
                            returnType.getTypeName() + "', but instruction does not specify a result variable ID");
        }
        var resultVar = func.getVariableById(instruction.resultId());
        if (resultVar == null) {
            throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                    "Result variable ID '" + instruction.resultId() + "' not found in function");
        }
        // TODO
        return Map.of();
    }

    @Override
    protected @NotNull Map<String, Object> getGenerationExtraData(@NotNull CGenHelper helper,
                                                                  @NotNull LirClassDef clazz,
                                                                  @NotNull LirFunctionDef func,
                                                                  @NotNull LirBasicBlock block,
                                                                  int insnIndex,
                                                                  @NotNull CallGlobalInsn instruction) {
        return super.getGenerationExtraData(helper, clazz, func, block, insnIndex, instruction);
    }
}

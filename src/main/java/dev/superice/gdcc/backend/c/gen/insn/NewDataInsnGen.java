package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CGenHelper;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.insn.LiteralStringInsn;
import dev.superice.gdcc.lir.insn.LiteralStringNameInsn;
import dev.superice.gdcc.lir.insn.NewDataInstruction;
import dev.superice.gdcc.lir.insn.LiteralFloatInsn;
import dev.superice.gdcc.lir.insn.LiteralIntInsn;
import dev.superice.gdcc.lir.insn.LiteralBoolInsn;
import dev.superice.gdcc.lir.insn.LiteralNullInsn;
import dev.superice.gdcc.lir.insn.LiteralNilInsn;
import dev.superice.gdcc.type.*;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;

public final class NewDataInsnGen extends TemplateInsnGen<NewDataInstruction> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(
                GdInstruction.LITERAL_STRING_NAME,
                GdInstruction.LITERAL_STRING,
                GdInstruction.LITERAL_FLOAT,
                GdInstruction.LITERAL_BOOL,
                GdInstruction.LITERAL_INT,
                GdInstruction.LITERAL_NULL,
                GdInstruction.LITERAL_NIL
        );
    }

    @Override
    protected @NotNull String getTemplatePath() {
        return "insn/new_data.ftl";
    }

    @Override
    protected Map<String, Object> validateInstruction(@NotNull CGenHelper helper, @NotNull LirClassDef clazz, @NotNull LirFunctionDef func,
                                                      @NotNull LirBasicBlock block,
                                                      int insnIndex,
                                                      @NotNull NewDataInstruction instruction) {
        if (instruction.resultId() == null) {
            throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                    "New data instruction missing result variable ID");
        }
        var resultVariable = func.getVariableById(Objects.requireNonNull(instruction.resultId()));
        if (resultVariable == null) {
            throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                    "Result variable ID " + instruction.resultId() + " does not exist");
        }
        switch (instruction) {
            case LiteralStringNameInsn _ -> {
                if (!(resultVariable.type() instanceof GdStringNameType)) {
                    throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                            "Result variable ID " + instruction.resultId() + " is not of string name type");
                }
            }
            case LiteralStringInsn _ -> {
                if (!(resultVariable.type() instanceof GdStringType)) {
                    throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                            "Result variable ID " + instruction.resultId() + " is not of string type");
                }
            }
            case LiteralFloatInsn _ -> {
                if (!(resultVariable.type() instanceof GdFloatType)) {
                    throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                            "Result variable ID " + instruction.resultId() + " is not of float type");
                }
            }
            case LiteralBoolInsn _ -> {
                if (!(resultVariable.type() instanceof GdBoolType)) {
                    throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                            "Result variable ID " + instruction.resultId() + " is not of bool type");
                }
            }
            case LiteralIntInsn _ -> {
                if (!(resultVariable.type() instanceof GdIntType)) {
                    throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                            "Result variable ID " + instruction.resultId() + " is not of int type");
                }
            }
            case LiteralNullInsn _ -> {
                if (!(resultVariable.type() instanceof GdObjectType)) {
                    throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                            "Result variable ID " + instruction.resultId() + " is not of object type");
                }
            }
            case LiteralNilInsn _ -> {
                if (!(resultVariable.type() instanceof GdVariantType || resultVariable.type() instanceof GdNilType)) {
                    throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                            "Result variable ID " + instruction.resultId() + " is not of variant/nil type");
                }
            }
            default -> throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                    "Unsupported new-data instruction: " + instruction.opcode().opcode());
        }
        return Map.of();
    }
}

package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CGenHelper;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.insn.LoadPropertyInsn;
import dev.superice.gdcc.scope.PropertyDef;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdVectorType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;

public final class LoadPropertyInsnGen extends TemplateInsnGen<LoadPropertyInsn> {
    @Override
    protected @NotNull String getTemplatePath() {
        return "insn/load_property.ftl";
    }

    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.LOAD_PROPERTY);
    }

    @Override
    protected void validateInstruction(@NotNull CGenHelper helper, @NotNull LirClassDef clazz, @NotNull LirFunctionDef func, @NotNull LirBasicBlock block, int insnIndex, @NotNull LoadPropertyInsn instruction) {
        var objectVar = func.getVariableById(instruction.objectId());
        if (objectVar == null) {
            throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                    "Object variable ID " + instruction.objectId() + " does not exist");
        }
        if (instruction.resultId() == null) {
            throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                    "Load property instruction missing result variable ID");
        }
        var resultVar = func.getVariableById(instruction.resultId());
        if (resultVar == null) {
            throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                    "Result variable ID " + instruction.resultId() + " does not exist");
        }
        if (resultVar.ref()) {
            throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                    "Result variable ID " + instruction.resultId() + " cannot be a reference that cannot be written into");
        }
        if (!(objectVar.type() instanceof GdObjectType) && !(objectVar.type() instanceof GdVectorType)) {
            throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                    "Object variable ID " + instruction.objectId() + " is not of object or vector type, but " + objectVar.type().getTypeName());
        }
        var registry = helper.context().classRegistry();
        if (objectVar.type() instanceof GdObjectType gdObjectType) {
            var classDef = registry.getClassDef(gdObjectType);
            if (classDef == null) {
                throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                        "Object variable ID " + instruction.objectId() + " has unknown class type " + gdObjectType.getTypeName());
            }
            PropertyDef propertyFound = null;
            for (var property : classDef.getProperties()) {
                if (property.getName().equals(instruction.propertyName())) {
                    propertyFound = property;
                    break;
                }
            }
            if (propertyFound == null) {
                throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                        "Property '" + instruction.propertyName() + "' not found in class " + classDef.getName());
            }
            var propertyType = propertyFound.getType();
            if (!registry.checkAssignable(propertyType, resultVar.type())) {
                throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                        "Result variable ID " + instruction.resultId() + " of type " + resultVar.type().getTypeName() +
                                " is not assignable from property '" + instruction.propertyName() + "' of type " + propertyType.getTypeName());
            }
        }
    }

    @Override
    protected @NotNull Map<String, Object> getGenerationExtraData(@NotNull CGenHelper helper, @NotNull LirClassDef clazz, @NotNull LirFunctionDef func, @NotNull LirBasicBlock block, int insnIndex, @NotNull LoadPropertyInsn instruction) {
        return Map.of(
                "genMode", getGenMode(helper, func, instruction),
                "gettingSelf", isLoadingInsideGetterSelf(helper, clazz, func, instruction),
                "gdccGetterName", getGdccGetterName(helper, clazz, func, instruction)
        );
    }

    private @NotNull String getGenMode(@NotNull CGenHelper helper,
                                      @NotNull LirFunctionDef func,
                                      @NotNull LoadPropertyInsn insn) {
        var objectVar = func.getVariableById(insn.objectId());
        Objects.requireNonNull(objectVar);
        if (objectVar.type() instanceof GdObjectType gdObjectType) {
            if (gdObjectType.checkGdccType(helper.context().classRegistry())) {
                return "gdcc";
            } else if (gdObjectType.checkEngineType(helper.context().classRegistry())) {
                return "engine";
            } else {
                return "general";
            }
        } else if (objectVar.type() instanceof GdVectorType) {
            return "builtin";
        }
        throw new IllegalStateException("Invalid object variable type for LOAD_PROPERTY instruction");
    }

    private boolean isLoadingInsideGetterSelf(@NotNull CGenHelper helper, @NotNull LirClassDef clazz, @NotNull LirFunctionDef func, @NotNull LoadPropertyInsn instruction) {
        if (func.isAbstract() || func.isStatic() || func.isLambda() || func.isVararg()) {
            return false;
        }
        if (func.getParameterCount() != 1) {
            return false;
        }
        var registry = helper.context().classRegistry();
        var objectVar = Objects.requireNonNull(func.getVariableById(instruction.objectId()));
        var objectType = objectVar.type();
        if (objectType instanceof GdObjectType gdObjectType) {
            if (!gdObjectType.checkGdccType(registry)) {
                return false;
            }
            if (!gdObjectType.getTypeName().equals(clazz.getName())) {
                return false;
            }
            // find property name
            for (var property : clazz.getProperties()) {
                if (property.getName().equals(instruction.propertyName())) {
                    // check if function is the getter for this property
                    var getter = property.getGetterFunc();
                    if (getter != null && getter.equals(func.getName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String getGdccGetterName(@NotNull CGenHelper helper, @NotNull LirClassDef clazz, @NotNull LirFunctionDef func, @NotNull LoadPropertyInsn instruction) {
        var registry = helper.context().classRegistry();
        var objectVar = Objects.requireNonNull(func.getVariableById(instruction.objectId()));
        var objectType = objectVar.type();
        if (objectType instanceof GdObjectType gdObjectType) {
            if (!gdObjectType.checkGdccType(registry)) {
                return "";
            }
            if (!gdObjectType.getTypeName().equals(clazz.getName())) {
                return "";
            }
            // find property name
            for (var property : clazz.getProperties()) {
                if (property.getName().equals(instruction.propertyName())) {
                    // check if function is the getter for this property
                    var getter = property.getGetterFunc();
                    if (getter != null && getter.equals(func.getName())) {
                        return getter;
                    }
                }
            }
        }
        return "";
    }
}

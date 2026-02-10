package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CGenHelper;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.insn.StorePropertyInsn;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdVectorType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;

public final class StorePropertyInsnGen extends TemplateInsnGen<StorePropertyInsn> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.STORE_PROPERTY);
    }

    @Override
    protected @NotNull String getTemplatePath() {
        return "insn/store_property.ftl";
    }

    @Override
    protected Map<String, Object> validateInstruction(@NotNull CGenHelper helper, @NotNull LirClassDef clazz, @NotNull LirFunctionDef func, @NotNull LirBasicBlock block, int insnIndex, @NotNull StorePropertyInsn instruction) {
        var objectVar = func.getVariableById(instruction.objectId());
        if (objectVar == null) {
            throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                    "Object variable ID " + instruction.objectId() + " does not exist");
        }
        var valueVar = func.getVariableById(instruction.valueId());
        if (valueVar == null) {
            throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                    "Value variable ID " + instruction.valueId() + " does not exist");
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
            // find the property in the class
            var propertyFound = classDef.getProperties().stream()
                    .filter(p -> p.getName().equals(instruction.propertyName()))
                    .findFirst()
                    .orElse(null);
            if (propertyFound == null) {
                throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                        "Property '" + instruction.propertyName() + "' not found in class " + classDef.getName());
            }
            var propertyType = propertyFound.getType();
            // valueVar.type() must be assignable to propertyType
            if (!registry.checkAssignable(propertyType, valueVar.type())) {
                throw new InvalidInsnException(func.getName(), block.id(), insnIndex, instruction.opcode().opcode(),
                        "Value variable ID " + instruction.valueId() + " of type " + valueVar.type().getTypeName() +
                                " is not assignable to property '" + instruction.propertyName() + "' of type " + propertyType.getTypeName());
            }
        }
        // TODO: validate other built-in types' properties
        return Map.of();
    }

    @Override
    protected @NotNull Map<String, Object> getGenerationExtraData(@NotNull CGenHelper helper, @NotNull LirClassDef clazz, @NotNull LirFunctionDef func, @NotNull LirBasicBlock block, int insnIndex, @NotNull StorePropertyInsn instruction) {
        return Map.of(
                "genMode", getGenMode(helper, func, instruction),
                "insideSelfSetter", isStoringInsideSetterSelf(helper, clazz, func, instruction),
                "gdccSetterName", getGdccSetterName(helper, clazz, func, instruction)
        );
    }

    private @NotNull String getGenMode(@NotNull CGenHelper helper,
                                      @NotNull LirFunctionDef func,
                                      @NotNull StorePropertyInsn insn) {
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
        throw new IllegalStateException("Invalid object variable type for STORE_PROPERTY instruction");
    }

    private boolean isStoringInsideSetterSelf(@NotNull CGenHelper helper, @NotNull LirClassDef clazz, @NotNull LirFunctionDef func, @NotNull StorePropertyInsn instruction) {
        if (func.isAbstract() || func.isStatic() || func.isLambda() || func.isVararg()) {
            return false;
        }
        // setter has two parameters: self, value
        if (func.getParameterCount() != 2) {
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
                    // check if function is the setter for this property
                    var setter = property.getSetterFunc();
                    if (setter != null && setter.equals(func.getName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String getGdccSetterName(@NotNull CGenHelper helper, @NotNull LirClassDef clazz, @NotNull LirFunctionDef func, @NotNull StorePropertyInsn instruction) {
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
                    // return the setter for this property
                    var setter = property.getSetterFunc();
                    if (setter != null) {
                        return setter;
                    }
                }
            }
        }
        return "";
    }
}

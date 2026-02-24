package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.lir.insn.StorePropertyInsn;
import dev.superice.gdcc.scope.PropertyDef;
import dev.superice.gdcc.type.GdNilType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

public final class StorePropertyInsnGen implements CInsnGen<StorePropertyInsn> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.STORE_PROPERTY);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var insn = bodyBuilder.getCurrentInsn(this);
        var func = bodyBuilder.func();
        var helper = bodyBuilder.helper();

        var objectVar = func.getVariableById(insn.objectId());
        if (objectVar == null) {
            throw bodyBuilder.invalidInsn("Object variable ID " + insn.objectId() + " does not exist");
        }
        var valueVar = func.getVariableById(insn.valueId());
        if (valueVar == null) {
            throw bodyBuilder.invalidInsn("Value variable ID " + insn.valueId() + " does not exist");
        }
        if (objectVar.type() instanceof GdVoidType || objectVar.type() instanceof GdNilType) {
            throw bodyBuilder.invalidInsn("Object variable ID " + insn.objectId() +
                    " is not a valid property target type, but " + objectVar.type().getTypeName());
        }

        // Validate property existence/writability and assignment compatibility first.
        validatePropertyWrite(bodyBuilder, objectVar.type(), valueVar.type(), insn.propertyName());
        var genMode = PropertyAccessResolver.resolveGenMode(bodyBuilder, func, insn.objectId(), "STORE_PROPERTY");

        switch (genMode) {
            case GDCC -> {
                var objectType = (GdObjectType) objectVar.type();
                var classDef = Objects.requireNonNull(bodyBuilder.classRegistry().getClassDef(objectType));
                var propertyDef = Objects.requireNonNull(PropertyAccessResolver.findPropertyDef(classDef, insn.propertyName()));
                if (isStoringInsideSetterSelf(bodyBuilder, insn, propertyDef)) {
                    var fieldTarget = bodyBuilder.targetOfExpr(
                            "$" + objectVar.id() + "->" + insn.propertyName(),
                            propertyDef.getType()
                    );
                    bodyBuilder.assignVar(fieldTarget, bodyBuilder.valueOfVar(valueVar));
                } else {
                    var setterName = propertyDef.getSetterFunc();
                    if (setterName == null || setterName.isEmpty()) {
                        throw bodyBuilder.invalidInsn("Property '" + insn.propertyName() + "' in class " +
                                classDef.getName() + " has no setter");
                    }
                    var setterCall = classDef.getName() + "_" + setterName;
                    bodyBuilder.callVoid(setterCall,
                            List.of(bodyBuilder.valueOfVar(objectVar), bodyBuilder.valueOfVar(valueVar)));
                }
            }
            case ENGINE, BUILTIN -> {
                var objectType = objectVar.type();
                var setterName = "godot_" + objectType.getTypeName() + "_set_" + insn.propertyName();
                bodyBuilder.callVoid(setterName,
                        List.of(bodyBuilder.valueOfVar(objectVar), bodyBuilder.valueOfVar(valueVar)));
            }
            case GENERAL -> {
                var packFunc = helper.renderPackFunctionName(valueVar.type());
                var tempVariant = bodyBuilder.newTempVariable("variant", GdVariantType.VARIANT);
                bodyBuilder.declareTempVar(tempVariant);
                bodyBuilder.callAssign(tempVariant, packFunc, GdVariantType.VARIANT, List.of(bodyBuilder.valueOfVar(valueVar)));
                bodyBuilder.callVoid(
                        "godot_Object_set",
                        List.of(
                                bodyBuilder.valueOfVar(objectVar),
                                bodyBuilder.valueOfStringNamePtrLiteral(insn.propertyName()),
                                tempVariant
                        )
                );
                bodyBuilder.destroyTempVar(tempVariant);
            }
            default -> throw bodyBuilder.invalidInsn("Unsupported STORE_PROPERTY generation mode: " + genMode);
        }
    }

    private void validatePropertyWrite(@NotNull CBodyBuilder bodyBuilder,
                                       @NotNull GdType objectType,
                                       @NotNull GdType valueType,
                                       @NotNull String propertyName) {
        var registry = bodyBuilder.classRegistry();
        if (objectType instanceof GdObjectType gdObjectType) {
            var classDef = registry.getClassDef(gdObjectType);
            if (classDef == null) {
                // Unknown object types are written through godot_Object_set and resolved at runtime.
                return;
            }
            var propertyFound = PropertyAccessResolver.findPropertyDef(classDef, propertyName);
            if (propertyFound == null) {
                throw bodyBuilder.invalidInsn("Property '" + propertyName + "' not found in class " + classDef.getName());
            }
            if (propertyFound instanceof ExtensionGdClass.PropertyInfo engineProperty && !engineProperty.isWritable()) {
                throw bodyBuilder.invalidInsn("Property '" + propertyName + "' in class " +
                        classDef.getName() + " is not writable");
            }
            var propertyType = propertyFound.getType();
            if (!registry.checkAssignable(valueType, propertyType)) {
                throw bodyBuilder.invalidInsn("Value type " + valueType.getTypeName() +
                        " is not assignable to property '" + propertyName +
                        "' of type " + propertyType.getTypeName());
            }
            return;
        }

        var lookup = PropertyAccessResolver.resolveBuiltinProperty(bodyBuilder, objectType, propertyName);
        var builtinClass = lookup.builtinClass();
        if (!lookup.property().isWritable()) {
            throw bodyBuilder.invalidInsn("Property '" + propertyName + "' in builtin class " +
                    builtinClass.getName() + " is not writable");
        }
        var propertyType = lookup.property().getType();
        if (!registry.checkAssignable(valueType, propertyType)) {
            throw bodyBuilder.invalidInsn("Value type " + valueType.getTypeName() +
                    " is not assignable to property '" + propertyName +
                    "' of type " + propertyType.getTypeName());
        }
    }

    private boolean isStoringInsideSetterSelf(@NotNull CBodyBuilder bodyBuilder,
                                              @NotNull StorePropertyInsn instruction,
                                              @NotNull PropertyDef propertyDef) {
        var func = bodyBuilder.func();
        if (func.isAbstract() || func.isStatic() || func.isLambda() || func.isVararg()) {
            return false;
        }
        // Setter has two parameters: self, value.
        if (func.getParameterCount() != 2) {
            return false;
        }
        var registry = bodyBuilder.classRegistry();
        var objectVar = Objects.requireNonNull(func.getVariableById(instruction.objectId()));
        var objectType = objectVar.type();
        if (objectType instanceof GdObjectType gdObjectType) {
            if (!gdObjectType.checkGdccType(registry)) {
                return false;
            }
            if (!gdObjectType.getTypeName().equals(bodyBuilder.clazz().getName())) {
                return false;
            }
            var setter = propertyDef.getSetterFunc();
            return setter != null && setter.equals(func.getName());
        }
        return false;
    }

}

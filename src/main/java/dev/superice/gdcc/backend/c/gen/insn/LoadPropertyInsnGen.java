package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.lir.insn.LoadPropertyInsn;
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

public final class LoadPropertyInsnGen implements CInsnGen<LoadPropertyInsn> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.LOAD_PROPERTY);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var insn = bodyBuilder.getCurrentInsn(this);
        var func = bodyBuilder.func();
        var helper = bodyBuilder.helper();

        if (insn.resultId() == null) {
            throw bodyBuilder.invalidInsn("Load property instruction missing result variable ID");
        }

        var objectVar = func.getVariableById(insn.objectId());
        if (objectVar == null) {
            throw bodyBuilder.invalidInsn("Object variable ID " + insn.objectId() + " does not exist");
        }
        var resultVar = func.getVariableById(insn.resultId());
        if (resultVar == null) {
            throw bodyBuilder.invalidInsn("Result variable ID " + insn.resultId() + " does not exist");
        }
        if (resultVar.ref()) {
            throw bodyBuilder.invalidInsn("Result variable ID " + insn.resultId() + " cannot be a reference that cannot be written into");
        }
        if (objectVar.type() instanceof GdVoidType || objectVar.type() instanceof GdNilType) {
            throw bodyBuilder.invalidInsn("Object variable ID " + insn.objectId() + " is not a valid property target type, but " + objectVar.type().getTypeName());
        }

        var registry = bodyBuilder.classRegistry();
        var propertyType = resolvePropertyType(bodyBuilder, objectVar.type(), resultVar.type(), insn.propertyName());
        var genMode = PropertyAccessResolver.resolveGenMode(bodyBuilder, func, insn.objectId(), "LOAD_PROPERTY");
        var target = bodyBuilder.targetOfVar(resultVar);

        switch (genMode) {
            case GDCC -> {
                var objectType = (GdObjectType) objectVar.type();
                var classDef = Objects.requireNonNull(registry.getClassDef(objectType));
                var propertyDef = Objects.requireNonNull(PropertyAccessResolver.findPropertyDef(classDef, insn.propertyName()));
                var inGetterSelf = isLoadingInsideGetterSelf(bodyBuilder, insn, propertyDef);
                if (inGetterSelf) {
                    var expr = "$" + objectVar.id() + "->" + insn.propertyName();
                    bodyBuilder.assignExpr(target, expr, propertyType);
                } else {
                    var getterName = propertyDef.getGetterFunc();
                    if (getterName == null || getterName.isEmpty()) {
                        throw bodyBuilder.invalidInsn("Property '" + insn.propertyName() + "' in class " + classDef.getName() + " has no getter");
                    }
                    var getterCall = classDef.getName() + "_" + getterName;
                    bodyBuilder.callAssign(target, getterCall, propertyType, List.of(bodyBuilder.valueOfVar(objectVar)));
                }
            }
            case ENGINE -> {
                var objectType = (GdObjectType) objectVar.type();
                var getterName = "godot_" + objectType.getTypeName() + "_get_" + insn.propertyName();
                var objectValue = bodyBuilder.valueOfVar(objectVar);
                bodyBuilder.callAssign(target, getterName, propertyType, List.of(objectValue));
            }
            case GENERAL -> {
                // Pass objectVar directly; renderArgument will auto-convert GDCC ptrs
                // to Godot raw ptrs via godot_object_from_gdcc_object_ptr(...) since godot_Object_get requires Godot ptrs.
                var objectValue = bodyBuilder.valueOfVar(objectVar);
                var propertyName = bodyBuilder.valueOfStringNamePtrLiteral(insn.propertyName());
                var tempVar = bodyBuilder.newTempVariable("variant", GdVariantType.VARIANT);
                bodyBuilder.declareTempVar(tempVar);
                bodyBuilder.callAssign(tempVar, "godot_Object_get", GdVariantType.VARIANT, List.of(objectValue, propertyName));
                var resultType = resultVar.type();
                var unpackFunc = helper.renderUnpackFunctionName(resultType);
                bodyBuilder.callAssign(target, unpackFunc, resultType, List.of(tempVar));
                bodyBuilder.destroyTempVar(tempVar);
            }
            case BUILTIN -> {
                var objectType = objectVar.type();
                var getterName = "godot_" + objectType.getTypeName() + "_get_" + insn.propertyName();
                var objectValue = bodyBuilder.valueOfVar(objectVar);
                bodyBuilder.callAssign(target, getterName, propertyType, List.of(objectValue));
            }
            default -> throw bodyBuilder.invalidInsn("Unsupported LOAD_PROPERTY generation mode: " + genMode);
        }
    }

    private @NotNull GdType resolvePropertyType(@NotNull CBodyBuilder bodyBuilder,
                                                @NotNull GdType objectType,
                                                @NotNull GdType resultType,
                                                @NotNull String propertyName) {
        var registry = bodyBuilder.classRegistry();
        if (objectType instanceof GdObjectType gdObjectType) {
            var classDef = registry.getClassDef(gdObjectType);
            if (classDef == null) {
                // Unknown object types are read through godot_Object_get and unpacked into the expected result type.
                return resultType;
            }
            var propertyFound = PropertyAccessResolver.findPropertyDef(classDef, propertyName);
            if (propertyFound == null) {
                throw bodyBuilder.invalidInsn("Property '" + propertyName + "' not found in class " + classDef.getName());
            }
            var propertyType = propertyFound.getType();
            if (!registry.checkAssignable(propertyType, resultType)) {
                throw bodyBuilder.invalidInsn("Result type " + resultType.getTypeName() +
                        " is not assignable from property '" + propertyName + "' of type " + propertyType.getTypeName());
            }
            return propertyType;
        }

        var lookup = PropertyAccessResolver.resolveBuiltinProperty(bodyBuilder, objectType, propertyName);
        var builtinClass = lookup.builtinClass();
        if (!lookup.property().isReadable()) {
            throw bodyBuilder.invalidInsn("Property '" + propertyName + "' in builtin class " + builtinClass.getName() + " is not readable");
        }
        var propertyType = lookup.property().getType();
        if (!registry.checkAssignable(propertyType, resultType)) {
            throw bodyBuilder.invalidInsn("Result type " + resultType.getTypeName() +
                    " is not assignable from property '" + propertyName + "' of type " + propertyType.getTypeName());
        }
        return propertyType;
    }

    private boolean isLoadingInsideGetterSelf(@NotNull CBodyBuilder bodyBuilder,
                                              @NotNull LoadPropertyInsn instruction,
                                              @NotNull PropertyDef propertyDef) {
        var func = bodyBuilder.func();
        if (func.isAbstract() || func.isStatic() || func.isLambda() || func.isVararg()) {
            return false;
        }
        if (func.getParameterCount() != 1) {
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
            var getter = propertyDef.getGetterFunc();
            return getter != null && getter.equals(func.getName());
        }
        return false;
    }

}

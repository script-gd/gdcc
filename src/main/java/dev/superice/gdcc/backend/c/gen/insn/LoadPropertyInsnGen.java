package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CGenHelper;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.insn.LoadPropertyInsn;
import dev.superice.gdcc.scope.ClassDef;
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

        var registry = helper.context().classRegistry();
        var propertyType = resolvePropertyType(bodyBuilder, objectVar.type(), resultVar.type(), insn.propertyName());
        var genMode = getGenMode(helper, func, insn);
        var target = bodyBuilder.targetOfVar(resultVar);

        switch (genMode) {
            case "gdcc" -> {
                var objectType = (GdObjectType) objectVar.type();
                var classDef = Objects.requireNonNull(registry.getClassDef(objectType));
                var propertyDef = Objects.requireNonNull(findPropertyDef(classDef, insn.propertyName()));
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
            case "engine" -> {
                var objectType = (GdObjectType) objectVar.type();
                var expr = "godot_" + objectType.getTypeName() + "_get_" + insn.propertyName() + "($" + objectVar.id() + ")";
                bodyBuilder.assignExpr(target, expr, propertyType);
            }
            case "general" -> {
                var objectType = (GdObjectType) objectVar.type();
                var objectRef = helper.checkGdccType(objectType) ? "$" + objectVar.id() + "->_object" : "$" + objectVar.id();
                var tempVar = bodyBuilder.newTempVariable(
                        "variant",
                        GdVariantType.VARIANT,
                        "godot_Object_get(" + objectRef + ", GD_STATIC_SN(u8\"" + insn.propertyName() + "\"))"
                );
                bodyBuilder.declareTempVar(tempVar);
                var resultType = resultVar.type();
                var unpackExpr = helper.renderUnpackFunctionName(resultType) + "(&" + tempVar.name() + ")";
                bodyBuilder.assignExpr(target, unpackExpr, resultType);
                bodyBuilder.destroyTempVar(tempVar);
            }
            case "builtin" -> {
                var objectRef = objectVar.ref() ? "$" + objectVar.id() : "&$" + objectVar.id();
                var objectType = objectVar.type();
                var expr = "godot_" + objectType.getTypeName() + "_get_" + insn.propertyName() + "(" + objectRef + ")";
                bodyBuilder.assignExpr(target, expr, propertyType);
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
                throw bodyBuilder.invalidInsn("Object variable has unknown class type " + gdObjectType.getTypeName());
            }
            var propertyFound = findPropertyDef(classDef, propertyName);
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

        var builtinClass = registry.findBuiltinClass(objectType.getTypeName());
        if (builtinClass == null) {
            throw bodyBuilder.invalidInsn("Builtin class not found for type " + objectType.getTypeName());
        }
        ExtensionBuiltinClass.PropertyInfo propertyFound = null;
        for (var property : builtinClass.getProperties()) {
            if (property.getName().equals(propertyName)) {
                propertyFound = (ExtensionBuiltinClass.PropertyInfo) property;
                break;
            }
        }
        if (propertyFound == null) {
            throw bodyBuilder.invalidInsn("Property '" + propertyName + "' not found in builtin class " + builtinClass.getName());
        }
        if (!propertyFound.isReadable()) {
            throw bodyBuilder.invalidInsn("Property '" + propertyName + "' in builtin class " + builtinClass.getName() + " is not readable");
        }
        var propertyType = propertyFound.getType();
        if (!registry.checkAssignable(propertyType, resultType)) {
            throw bodyBuilder.invalidInsn("Result type " + resultType.getTypeName() +
                    " is not assignable from property '" + propertyName + "' of type " + propertyType.getTypeName());
        }
        return propertyType;
    }

    private @NotNull String getGenMode(@NotNull CGenHelper helper,
                                       @NotNull LirFunctionDef func,
                                       @NotNull LoadPropertyInsn insn) {
        var objectVar = Objects.requireNonNull(func.getVariableById(insn.objectId()));
        if (objectVar.type() instanceof GdObjectType gdObjectType) {
            if (gdObjectType.checkGdccType(helper.context().classRegistry())) {
                return "gdcc";
            } else if (gdObjectType.checkEngineType(helper.context().classRegistry())) {
                return "engine";
            } else {
                return "general";
            }
        } else if (objectVar.type() instanceof GdVoidType || objectVar.type() instanceof GdNilType) {
            throw new IllegalStateException("Invalid object variable type for LOAD_PROPERTY instruction");
        }
        return "builtin";
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

    private PropertyDef findPropertyDef(@NotNull ClassDef classDef, @NotNull String propertyName) {
        for (var property : classDef.getProperties()) {
            if (property.getName().equals(propertyName)) {
                return property;
            }
        }
        return null;
    }
}

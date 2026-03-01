package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.lir.insn.LoadPropertyInsn;
import dev.superice.gdcc.type.GdNilType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

public final class LoadPropertyInsnGen implements CInsnGen<LoadPropertyInsn> {
    private record PropertyReadResolution(@NotNull GdType propertyType,
                                          @Nullable PropertyAccessResolver.ObjectPropertyLookup objectLookup) {
        private PropertyReadResolution {
            Objects.requireNonNull(propertyType);
        }
    }

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

        var readResolution = resolvePropertyRead(bodyBuilder, objectVar.type(), resultVar.type(), insn.propertyName());
        var propertyType = readResolution.propertyType();
        var genMode = PropertyAccessResolver.resolveGenMode(bodyBuilder, func, insn.objectId(), "LOAD_PROPERTY");
        var target = bodyBuilder.targetOfVar(resultVar);

        switch (genMode) {
            case OBJECT -> {
                var objectType = (GdObjectType) objectVar.type();
                var lookup = readResolution.objectLookup();
                if (lookup == null) {
                    throw bodyBuilder.invalidInsn("Missing owner lookup for known object receiver type '" +
                            objectType.getTypeName() + "' in LOAD_PROPERTY");
                }
                var receiverValue = PropertyAccessResolver.renderOwnerReceiverValue(
                        bodyBuilder,
                        objectVar,
                        lookup,
                        "LOAD_PROPERTY"
                );
                switch (lookup.ownerDispatchMode()) {
                    case GDCC -> {
                        var ownerClassName = lookup.ownerClass().getName();
                        var inGetterSelf = isLoadingInsideGetterSelf(bodyBuilder, objectVar, lookup);
                        if (inGetterSelf) {
                            var expr = "$" + objectVar.id() + "->" + insn.propertyName();
                            bodyBuilder.assignExpr(target, expr, propertyType);
                            break;
                        }
                        var getterName = lookup.property().getGetterFunc();
                        if (getterName == null || getterName.isEmpty()) {
                            throw bodyBuilder.invalidInsn("Property '" + insn.propertyName() + "' in class " +
                                    ownerClassName + " has no getter");
                        }
                        var getterCall = ownerClassName + "_" + getterName;
                        bodyBuilder.callAssign(target, getterCall, propertyType, List.of(receiverValue));
                    }
                    case ENGINE -> {
                        var getterName = "godot_" + lookup.ownerClass().getName() + "_get_" + insn.propertyName();
                        bodyBuilder.callAssign(target, getterName, propertyType, List.of(receiverValue));
                    }
                }
            }
            case GENERAL -> {
                // Pass objectVar directly; renderArgument will auto-convert GDCC ptrs
                // to Godot raw ptrs via gdcc_object_to_godot_object_ptr(value, Type_object_ptr) since godot_Object_get requires Godot ptrs.
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

    private @NotNull PropertyReadResolution resolvePropertyRead(@NotNull CBodyBuilder bodyBuilder,
                                                                @NotNull GdType objectType,
                                                                @NotNull GdType resultType,
                                                                @NotNull String propertyName) {
        var registry = bodyBuilder.classRegistry();
        if (objectType instanceof GdObjectType gdObjectType) {
            var lookup = PropertyAccessResolver.resolveObjectProperty(
                    bodyBuilder,
                    gdObjectType,
                    propertyName,
                    "LOAD_PROPERTY"
            );
            if (lookup == null) {
                // Unknown object types are read through godot_Object_get and unpacked into the expected result type.
                return new PropertyReadResolution(resultType, null);
            }
            var propertyType = lookup.property().getType();
            if (!registry.checkAssignable(propertyType, resultType)) {
                throw bodyBuilder.invalidInsn("Result type " + resultType.getTypeName() +
                        " is not assignable from property '" + propertyName + "' of type " + propertyType.getTypeName());
            }
            return new PropertyReadResolution(propertyType, lookup);
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
        return new PropertyReadResolution(propertyType, null);
    }

    private boolean isLoadingInsideGetterSelf(@NotNull CBodyBuilder bodyBuilder,
                                              @NotNull dev.superice.gdcc.lir.LirVariable objectVar,
                                              @NotNull PropertyAccessResolver.ObjectPropertyLookup lookup) {
        var func = bodyBuilder.func();
        if (func.isAbstract() || func.isStatic() || func.isLambda() || func.isVararg()) {
            return false;
        }
        if (func.getParameterCount() != 1) {
            return false;
        }
        var ownerClassName = lookup.ownerClass().getName();
        if (!ownerClassName.equals(bodyBuilder.clazz().getName())) {
            return false;
        }
        var registry = bodyBuilder.classRegistry();
        var objectType = objectVar.type();
        if (objectType instanceof GdObjectType gdObjectType) {
            if (!gdObjectType.checkGdccType(registry)) {
                return false;
            }
            if (!gdObjectType.getTypeName().equals(ownerClassName)) {
                return false;
            }
            var getter = lookup.property().getGetterFunc();
            return getter != null && getter.equals(func.getName());
        }
        return false;
    }

}

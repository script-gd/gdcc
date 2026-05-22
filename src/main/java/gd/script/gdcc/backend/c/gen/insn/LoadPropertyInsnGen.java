package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.lir.insn.LoadPropertyInsn;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

public final class LoadPropertyInsnGen implements CInsnGen<LoadPropertyInsn> {
    private record PropertyReadResolution(@NotNull GdType propertyType,
                                          @Nullable BackendPropertyAccessResolver.ObjectPropertyLookup objectLookup) {
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
        var target = bodyBuilder.targetOfVar(resultVar);

        if (objectVar.type() instanceof GdObjectType) {
            var lookup = readResolution.objectLookup();
            if (lookup != null) {
                var receiverValue = BackendPropertyAccessResolver.renderOwnerReceiverValue(
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
                            // Getter-self fast path must treat the backing field as existing storage.
                            // Value-semantic types need copy-by-address from `&self->field`, not
                            // `tmp = self->field; copy(&tmp); destroy(tmp)`, otherwise the temp destroy
                            // can invalidate the field's engine-side storage.
                            bodyBuilder.assignVar(target, bodyBuilder.valueOfAddressableExpr(expr, propertyType));
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
                        var accessor = BackendPropertyAccessResolver.resolveEnginePropertyReadAccessor(
                                bodyBuilder,
                                lookup,
                                "LOAD_PROPERTY"
                        );
                        var args = new ArrayList<CBodyBuilder.ValueRef>(accessor.index() == null ? 1 : 2);
                        args.add(receiverValue);
                        if (accessor.index() != null) {
                            args.add(bodyBuilder.valueOfExpr(Integer.toString(accessor.index()), GdIntType.INT));
                        }
                        bodyBuilder.callAssign(target, accessor.cFunctionName(), accessor.returnType(), args);
                        bodyBuilder.recordUsedEngineMethodCall(accessor.toResolvedMethodCall());
                    }
                }
            } else {
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
        } else {
            var getterName = "godot_" + objectVar.type().getTypeName() + "_get_" + insn.propertyName();
            var objectValue = bodyBuilder.valueOfVar(objectVar);
            bodyBuilder.callAssign(target, getterName, propertyType, List.of(objectValue));
        }
    }

    private @NotNull PropertyReadResolution resolvePropertyRead(@NotNull CBodyBuilder bodyBuilder,
                                                                @NotNull GdType objectType,
                                                                @NotNull GdType resultType,
                                                                @NotNull String propertyName) {
        var registry = bodyBuilder.classRegistry();
        if (objectType instanceof GdObjectType gdObjectType) {
            var lookup = BackendPropertyAccessResolver.resolveObjectProperty(
                    bodyBuilder,
                    gdObjectType,
                    propertyName,
                    "LOAD_PROPERTY"
            );
            if (lookup == null) {
                // Unknown object types are read through godot_Object_get and unpacked into the expected result type.
                return new PropertyReadResolution(resultType, null);
            }
            if (lookup.property() instanceof ExtensionGdClass.PropertyInfo engineProperty &&
                    !engineProperty.isReadable()) {
                throw bodyBuilder.invalidInsn("Property '" + propertyName + "' in class " +
                        lookup.ownerClass().getName() + " is not readable");
            }
            var propertyType = lookup.property().getType();
            if (!registry.checkAssignable(propertyType, resultType)) {
                throw bodyBuilder.invalidInsn("Result type " + resultType.getTypeName() +
                        " is not assignable from property '" + propertyName + "' of type " + propertyType.getTypeName());
            }
            return new PropertyReadResolution(propertyType, lookup);
        }

        var lookup = BackendPropertyAccessResolver.resolveBuiltinProperty(bodyBuilder, objectType, propertyName);
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
                                              @NotNull LirVariable objectVar,
                                              @NotNull BackendPropertyAccessResolver.ObjectPropertyLookup lookup) {
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

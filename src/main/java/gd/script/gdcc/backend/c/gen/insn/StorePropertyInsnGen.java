package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.lir.insn.StorePropertyInsn;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public final class StorePropertyInsnGen implements CInsnGen<StorePropertyInsn> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.STORE_PROPERTY);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var insn = bodyBuilder.getCurrentInsn(this);
        var func = bodyBuilder.func();

        var objectVar = func.getVariableById(insn.objectId());
        if (objectVar == null) {
            throw bodyBuilder.invalidInsn("Object variable ID " + insn.objectId() + " does not exist");
        }
        InsnGenSupport.rejectCompilerOnlyVariable(bodyBuilder, objectVar, "property store receiver");
        var valueVar = func.getVariableById(insn.valueId());
        if (valueVar == null) {
            throw bodyBuilder.invalidInsn("Value variable ID " + insn.valueId() + " does not exist");
        }
        InsnGenSupport.rejectCompilerOnlyVariable(bodyBuilder, valueVar, "property store value");
        if (objectVar.type() instanceof GdVoidType || objectVar.type() instanceof GdNilType) {
            throw bodyBuilder.invalidInsn("Object variable ID " + insn.objectId() +
                    " is not a valid property target type, but " + objectVar.type().getTypeName());
        }

        // Validate property existence/writability and assignment compatibility first.
        var objectLookup = validatePropertyWrite(bodyBuilder, objectVar.type(), valueVar.type(), insn.propertyName());

        if (objectVar.type() instanceof GdObjectType ownerObjectType) {
            if (objectLookup != null) {
                var receiverValue = BackendPropertyAccessResolver.renderOwnerReceiverValue(
                        bodyBuilder,
                        objectVar,
                        objectLookup,
                        "STORE_PROPERTY"
                );
                switch (objectLookup.ownerDispatchMode()) {
                    case GDCC -> {
                        var ownerClassName = objectLookup.ownerClass().getName();
                        if (isStoringInsideSetterSelf(bodyBuilder, objectVar, objectLookup)) {
                            var liveOwner = BackendPropertyAccessResolver.renderGdccLiveOwnerPointerExpr(
                                    bodyBuilder,
                                    objectVar,
                                    ownerObjectType
                            );
                            var fieldTarget = bodyBuilder.targetOfExpr(
                                    liveOwner + "->" + insn.propertyName(),
                                    objectLookup.property().getType()
                            );
                            bodyBuilder.assignVar(fieldTarget, bodyBuilder.valueOfVar(valueVar));
                            break;
                        }
                        var setterName = objectLookup.property().getSetterFunc();
                        if (setterName == null || setterName.isEmpty()) {
                            throw bodyBuilder.invalidInsn("Property '" + insn.propertyName() + "' in class " +
                                    ownerClassName + " has no setter");
                        }
                        var setterCall = ownerClassName + "_" + setterName;
                        bodyBuilder.callVoid(setterCall,
                                List.of(receiverValue, renderPropertyStoreValue(bodyBuilder, valueVar, objectLookup.property().getType())));
                    }
                    case ENGINE -> {
                        var accessor = BackendPropertyAccessResolver.resolveEnginePropertyWriteAccessor(
                                bodyBuilder,
                                objectLookup,
                                "STORE_PROPERTY"
                        );
                        var args = new ArrayList<CBodyBuilder.ValueRef>(accessor.index() == null ? 2 : 3);
                        args.add(receiverValue);
                        if (accessor.index() != null) {
                            args.add(bodyBuilder.valueOfExpr(Integer.toString(accessor.index()), GdIntType.INT));
                        }
                        args.add(renderPropertyStoreValue(bodyBuilder, valueVar, objectLookup.property().getType()));
                        bodyBuilder.callVoid(accessor.cFunctionName(), args);
                        bodyBuilder.recordUsedEngineMethodCall(accessor.toResolvedMethodCall());
                    }
                }
            } else {
                var tempVariant = bodyBuilder.newTempVariable("variant", GdVariantType.VARIANT);
                bodyBuilder.declareTempVar(tempVariant);
                InsnGenSupport.packVariantAssign(bodyBuilder, tempVariant, valueVar);
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
        } else {
            var setterName = "godot_" + objectVar.type().getTypeName() + "_set_" + insn.propertyName();
            bodyBuilder.callVoid(setterName,
                    List.of(bodyBuilder.valueOfVar(objectVar), bodyBuilder.valueOfVar(valueVar)));
        }
    }

    private @Nullable BackendPropertyAccessResolver.ObjectPropertyLookup validatePropertyWrite(@NotNull CBodyBuilder bodyBuilder,
                                                                                               @NotNull GdType objectType,
                                                                                               @NotNull GdType valueType,
                                                                                               @NotNull String propertyName) {
        var registry = bodyBuilder.classRegistry();
        if (objectType instanceof GdObjectType gdObjectType) {
            var lookup = BackendPropertyAccessResolver.resolveObjectProperty(
                    bodyBuilder,
                    gdObjectType,
                    propertyName,
                    "STORE_PROPERTY"
            );
            if (lookup == null) {
                // Unknown object types are written through godot_Object_set and resolved at runtime.
                return null;
            }
            var propertyDef = lookup.property();
            if (propertyDef instanceof ExtensionGdClass.PropertyInfo engineProperty && !engineProperty.isWritable()) {
                throw bodyBuilder.invalidInsn("Property '" + propertyName + "' in class " +
                        lookup.ownerClass().getName() + " is not writable");
            }
            var propertyType = propertyDef.getType();
            if (!registry.checkAssignable(valueType, propertyType)) {
                throw bodyBuilder.invalidInsn("Value type " + valueType.getTypeName() +
                        " is not assignable to property '" + propertyName +
                        "' of type " + propertyType.getTypeName());
            }
            return lookup;
        }

        var lookup = BackendPropertyAccessResolver.resolveBuiltinProperty(bodyBuilder, objectType, propertyName);
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
        return null;
    }

    private static @NotNull CBodyBuilder.ValueRef renderPropertyStoreValue(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull LirVariable valueVar,
            @NotNull GdType propertyType
    ) {
        if (valueVar.type() instanceof GdObjectType
                && propertyType instanceof GdObjectType propertyObjType
                && !valueVar.type().getTypeName().equals(propertyObjType.getTypeName())) {
            return bodyBuilder.valueOfCastedVar(valueVar, propertyObjType);
        }
        return bodyBuilder.valueOfVar(valueVar);
    }

    private boolean isStoringInsideSetterSelf(@NotNull CBodyBuilder bodyBuilder,
                                              @NotNull LirVariable objectVar,
                                              @NotNull BackendPropertyAccessResolver.ObjectPropertyLookup lookup) {
        var func = bodyBuilder.func();
        if (func.isAbstract() || func.isStatic() || func.isLambda() || func.isVararg()) {
            return false;
        }
        // Setter has two parameters: self, value.
        if (func.getParameterCount() != 2) {
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
            var setter = lookup.property().getSetterFunc();
            return setter != null && setter.equals(func.getName());
        }
        return false;
    }

}

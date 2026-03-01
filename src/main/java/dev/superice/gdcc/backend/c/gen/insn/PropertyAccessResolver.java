package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.scope.ClassDef;
import dev.superice.gdcc.scope.PropertyDef;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;

/// Shared resolver for load/store property codegen.
///
/// Responsibilities:
/// - Resolve GDCC/engine property definitions from class metadata.
/// - Resolve builtin property metadata from extension API metadata.
final class PropertyAccessResolver {
    private PropertyAccessResolver() {
    }

    record BuiltinPropertyLookup(@NotNull ExtensionBuiltinClass builtinClass,
                                 @NotNull ExtensionBuiltinClass.PropertyInfo property) {
        BuiltinPropertyLookup {
            Objects.requireNonNull(builtinClass);
            Objects.requireNonNull(property);
        }
    }

    enum PropertyOwnerDispatchMode {
        GDCC,
        ENGINE
    }

    record ObjectPropertyLookup(@NotNull ClassDef ownerClass,
                                @NotNull PropertyDef property,
                                @NotNull PropertyOwnerDispatchMode ownerDispatchMode) {
        ObjectPropertyLookup {
            Objects.requireNonNull(ownerClass);
            Objects.requireNonNull(property);
            Objects.requireNonNull(ownerDispatchMode);
        }
    }

    static @Nullable ObjectPropertyLookup resolveObjectProperty(@NotNull CBodyBuilder bodyBuilder,
                                                                @NotNull GdObjectType receiverType,
                                                                @NotNull String propertyName,
                                                                @NotNull String insnName) {
        var registry = bodyBuilder.classRegistry();
        var classDef = registry.getClassDef(receiverType);
        if (classDef == null) {
            return null;
        }

        ClassDef current = classDef;
        var visited = new HashSet<String>();
        var hierarchyNames = new ArrayList<String>();
        while (true) {
            hierarchyNames.add(current.getName());
            if (!visited.add(current.getName())) {
                throw bodyBuilder.invalidInsn("Detected inheritance cycle while resolving property '" +
                        propertyName + "' in " + insnName + " for class hierarchy '" +
                        String.join(" -> ", hierarchyNames) + "'");
            }

            var propertyDef = findOwnPropertyDef(current, propertyName);
            if (propertyDef != null) {
                return new ObjectPropertyLookup(
                        current,
                        propertyDef,
                        resolveOwnerDispatchMode(bodyBuilder, current, propertyName, insnName)
                );
            }

            var superName = current.getSuperName();
            if (superName.isBlank()) {
                break;
            }
            var superClassDef = registry.getClassDef(new GdObjectType(superName));
            if (superClassDef == null) {
                throw bodyBuilder.invalidInsn("Missing class metadata for super class '" + superName +
                        "' while resolving property '" + propertyName + "' in " + insnName +
                        " for receiver type '" + receiverType.getTypeName() + "'");
            }
            current = superClassDef;
        }
        throw bodyBuilder.invalidInsn("Property '" + propertyName + "' not found in class hierarchy of '" +
                receiverType.getTypeName() + "' in " + insnName + ": " +
                String.join(" -> ", hierarchyNames));
    }

    /// Convert resolved property owner metadata to object type used by cast/upcast rendering.
    static @NotNull GdObjectType toOwnerObjectType(@NotNull ObjectPropertyLookup lookup) {
        return new GdObjectType(lookup.ownerClass().getName());
    }

    /// Render receiver value aligned to resolved property owner.
    ///
    /// Receiver Value means the final C argument expression passed as
    /// the "self/this receiver" in generated method/property calls.
    /// It may be the original variable expression (`$var`) or an upcast
    /// expression produced by `valueOfCastedVar(...)`.
    ///
    /// This wrapper keeps property-path error wording stable while delegating
    /// actual cast/upcast logic to `renderReceiverValue`.
    static @NotNull CBodyBuilder.ValueRef renderOwnerReceiverValue(@NotNull CBodyBuilder bodyBuilder,
                                                                   @NotNull LirVariable receiverVar,
                                                                   @NotNull ObjectPropertyLookup lookup,
                                                                   @NotNull String insnName) {
        var ownerType = toOwnerObjectType(lookup);
        return renderReceiverValue(
                bodyBuilder,
                receiverVar,
                ownerType,
                insnName,
                "property owner",
                " for property '" + lookup.property().getName() + "'"
        );
    }

    /// Render a receiver value for a specific owner type.
    ///
    /// Receiver Value is an argument-shape concern, not a lifecycle ownership
    /// concern: this helper only decides which expression to pass as receiver.
    /// Retain/release semantics are handled by later assignment/call paths.
    ///
    /// Behavior:
    /// - Same static type: return `valueOfVar(receiverVar)`.
    /// - Different object types: enforce assignability, then return `valueOfCastedVar(...)`
    ///   so GDCC/ENGINE upcast strategy stays centralized in `CBodyBuilder`.
    /// - Non-assignable receiver/object owner pair: fail-fast with context-rich `invalidInsn`.
    static @NotNull CBodyBuilder.ValueRef renderReceiverValue(@NotNull CBodyBuilder bodyBuilder,
                                                              @NotNull LirVariable receiverVar,
                                                              @NotNull GdType ownerType,
                                                              @NotNull String insnName,
                                                              @NotNull String ownerRole,
                                                              @NotNull String messageTail) {
        if (receiverVar.type() instanceof GdObjectType receiverObjectType &&
                ownerType instanceof GdObjectType ownerObjectType &&
                !ownerObjectType.getTypeName().equals(receiverObjectType.getTypeName())) {
            if (!bodyBuilder.classRegistry().checkAssignable(receiverObjectType, ownerType)) {
                throw bodyBuilder.invalidInsn("Receiver type '" + receiverObjectType.getTypeName() +
                        "' is not assignable to " + ownerRole + " type '" + ownerObjectType.getTypeName() +
                        "' in " + insnName + messageTail);
            }
            return bodyBuilder.valueOfCastedVar(receiverVar, ownerObjectType);
        }
        return bodyBuilder.valueOfVar(receiverVar);
    }

    private static @Nullable PropertyDef findOwnPropertyDef(@NotNull ClassDef classDef,
                                                            @NotNull String propertyName) {
        for (var property : classDef.getProperties()) {
            if (property.getName().equals(propertyName)) {
                return property;
            }
        }
        return null;
    }

    private static @NotNull PropertyOwnerDispatchMode resolveOwnerDispatchMode(@NotNull CBodyBuilder bodyBuilder,
                                                                               @NotNull ClassDef ownerClass,
                                                                               @NotNull String propertyName,
                                                                               @NotNull String insnName) {
        var ownerClassName = ownerClass.getName();
        var registry = bodyBuilder.classRegistry();
        if (registry.isGdccClass(ownerClassName)) {
            return PropertyOwnerDispatchMode.GDCC;
        }
        if (registry.isGdClass(ownerClassName)) {
            return PropertyOwnerDispatchMode.ENGINE;
        }
        throw bodyBuilder.invalidInsn("Unsupported property owner '" + ownerClassName +
                "' while resolving property '" + propertyName + "' in " + insnName +
                ": expected GDCC or ENGINE class");
    }

    static @NotNull BuiltinPropertyLookup resolveBuiltinProperty(@NotNull CBodyBuilder bodyBuilder,
                                                                 @NotNull GdType objectType,
                                                                 @NotNull String propertyName) {
        var builtinClass = bodyBuilder.classRegistry().findBuiltinClass(objectType.getTypeName());
        if (builtinClass == null) {
            throw bodyBuilder.invalidInsn("Builtin class not found for type " + objectType.getTypeName());
        }
        for (var property : builtinClass.getProperties()) {
            if (property.getName().equals(propertyName)) {
                return new BuiltinPropertyLookup(builtinClass, (ExtensionBuiltinClass.PropertyInfo) property);
            }
        }
        throw bodyBuilder.invalidInsn("Property '" + propertyName + "' not found in builtin class " +
                builtinClass.getName());
    }
}

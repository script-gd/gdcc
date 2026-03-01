package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.scope.ClassDef;
import dev.superice.gdcc.scope.PropertyDef;
import dev.superice.gdcc.type.GdNilType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;

/// Shared resolver for load/store property codegen.
///
/// Responsibilities:
/// - Resolve property access generation mode for a receiver variable.
/// - Resolve GDCC/engine property definitions from class metadata.
/// - Resolve builtin property metadata from extension API metadata.
final class PropertyAccessResolver {
    private PropertyAccessResolver() {
    }

    enum GenMode {
        OBJECT,
        GENERAL,
        BUILTIN
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

    static @NotNull GenMode resolveGenMode(@NotNull CBodyBuilder bodyBuilder,
                                           @NotNull LirFunctionDef func,
                                           @NotNull String objectId,
                                           @NotNull String insnName) {
        var objectVar = Objects.requireNonNull(func.getVariableById(objectId));
        if (objectVar.type() instanceof GdObjectType gdObjectType) {
            if (gdObjectType.checkGdccType(bodyBuilder.classRegistry())) {
                return GenMode.OBJECT;
            }
            if (gdObjectType.checkEngineType(bodyBuilder.classRegistry())) {
                return GenMode.OBJECT;
            }
            return GenMode.GENERAL;
        }
        if (objectVar.type() instanceof GdVoidType || objectVar.type() instanceof GdNilType) {
            throw new IllegalStateException("Invalid object variable type for " + insnName + " instruction");
        }
        return GenMode.BUILTIN;
    }

    @Deprecated()
    static @Nullable PropertyDef findPropertyDef(@NotNull ClassDef classDef, @NotNull String propertyName) {
        return findOwnPropertyDef(classDef, propertyName);
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

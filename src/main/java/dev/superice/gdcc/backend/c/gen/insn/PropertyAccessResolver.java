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
        GDCC,
        ENGINE,
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

    static @NotNull GenMode resolveGenMode(@NotNull CBodyBuilder bodyBuilder,
                                           @NotNull LirFunctionDef func,
                                           @NotNull String objectId,
                                           @NotNull String insnName) {
        var objectVar = Objects.requireNonNull(func.getVariableById(objectId));
        if (objectVar.type() instanceof GdObjectType gdObjectType) {
            if (gdObjectType.checkGdccType(bodyBuilder.classRegistry())) {
                return GenMode.GDCC;
            }
            if (gdObjectType.checkEngineType(bodyBuilder.classRegistry())) {
                return GenMode.ENGINE;
            }
            return GenMode.GENERAL;
        }
        if (objectVar.type() instanceof GdVoidType || objectVar.type() instanceof GdNilType) {
            throw new IllegalStateException("Invalid object variable type for " + insnName + " instruction");
        }
        return GenMode.BUILTIN;
    }

    static @Nullable PropertyDef findPropertyDef(@NotNull ClassDef classDef, @NotNull String propertyName) {
        for (var property : classDef.getProperties()) {
            if (property.getName().equals(propertyName)) {
                return property;
            }
        }
        return null;
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


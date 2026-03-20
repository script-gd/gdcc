package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.scope.ClassDef;
import dev.superice.gdcc.scope.PropertyDef;
import dev.superice.gdcc.scope.ScopeOwnerKind;
import dev.superice.gdcc.scope.resolver.ScopePropertyResolver;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Backend adapter for the shared property metadata resolver.
///
/// Responsibilities kept in backend after Phase 5:
/// - translate shared lookup results into legacy backend-specific lookup records
/// - preserve stable `invalidInsn(...)` wording expected by existing codegen tests
/// - render owner-aligned receiver expressions for generated C calls
public final class BackendPropertyAccessResolver {
    private BackendPropertyAccessResolver() {
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
        var result = ScopePropertyResolver.resolveObjectProperty(bodyBuilder.classRegistry(), receiverType, propertyName);
        return switch (result) {
            case ScopePropertyResolver.Resolved resolved -> new ObjectPropertyLookup(
                    resolved.property().ownerClass(),
                    resolved.property().property(),
                    toOwnerDispatchMode(bodyBuilder, resolved.property().ownerKind(), propertyName, insnName)
            );
            case ScopePropertyResolver.MetadataUnknown _ -> null;
            case ScopePropertyResolver.Failed failed -> throw bodyBuilder.invalidInsn(
                    renderObjectFailureMessage(failed, insnName)
            );
        };
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

    static @NotNull BuiltinPropertyLookup resolveBuiltinProperty(@NotNull CBodyBuilder bodyBuilder,
                                                                 @NotNull GdType objectType,
                                                                 @NotNull String propertyName) {
        var result = ScopePropertyResolver.resolveBuiltinProperty(bodyBuilder.classRegistry(), objectType, propertyName);
        return switch (result) {
            case ScopePropertyResolver.Resolved resolved -> new BuiltinPropertyLookup(
                    (ExtensionBuiltinClass) resolved.property().ownerClass(),
                    (ExtensionBuiltinClass.PropertyInfo) resolved.property().property()
            );
            case ScopePropertyResolver.Failed failed -> throw bodyBuilder.invalidInsn(
                    renderBuiltinFailureMessage(failed)
            );
        };
    }

    private static @NotNull PropertyOwnerDispatchMode toOwnerDispatchMode(@NotNull CBodyBuilder bodyBuilder,
                                                                          @NotNull ScopeOwnerKind ownerKind,
                                                                          @NotNull String propertyName,
                                                                          @NotNull String insnName) {
        return switch (ownerKind) {
            case GDCC -> PropertyOwnerDispatchMode.GDCC;
            case ENGINE -> PropertyOwnerDispatchMode.ENGINE;
            case BUILTIN -> throw bodyBuilder.invalidInsn(
                    "Unsupported property owner 'builtin' while resolving property '" + propertyName +
                            "' in " + insnName + ": expected GDCC or ENGINE class"
            );
        };
    }

    private static @NotNull String renderObjectFailureMessage(@NotNull ScopePropertyResolver.Failed failed,
                                                              @NotNull String insnName) {
        return switch (failed.kind()) {
            case INHERITANCE_CYCLE -> "Detected inheritance cycle while resolving property '" +
                    failed.propertyName() + "' in " + insnName + " for class hierarchy '" +
                    String.join(" -> ", failed.hierarchy()) + "'";
            case MISSING_SUPER_METADATA -> "Missing class metadata for super class '" + failed.relatedClassName() +
                    "' while resolving property '" + failed.propertyName() + "' in " + insnName +
                    " for receiver type '" + failed.receiverType().getTypeName() + "'";
            case PROPERTY_MISSING -> "Property '" + failed.propertyName() + "' not found in class hierarchy of '" +
                    failed.receiverType().getTypeName() + "' in " + insnName + ": " +
                    String.join(" -> ", failed.hierarchy());
            case UNSUPPORTED_OWNER -> "Unsupported property owner '" + failed.ownerClassName() +
                    "' while resolving property '" + failed.propertyName() + "' in " + insnName +
                    ": expected GDCC or ENGINE class";
            case BUILTIN_CLASS_NOT_FOUND, BUILTIN_PROPERTY_MISSING -> throw new IllegalStateException(
                    "Builtin failure should not be rendered as object-property failure"
            );
        };
    }

    private static @NotNull String renderBuiltinFailureMessage(@NotNull ScopePropertyResolver.Failed failed) {
        return switch (failed.kind()) {
            case BUILTIN_CLASS_NOT_FOUND -> "Builtin class not found for type " + failed.receiverType().getTypeName();
            case BUILTIN_PROPERTY_MISSING -> "Property '" + failed.propertyName() +
                    "' not found in builtin class " + failed.ownerClassName();
            case INHERITANCE_CYCLE, MISSING_SUPER_METADATA, PROPERTY_MISSING, UNSUPPORTED_OWNER ->
                    throw new IllegalStateException("Object-property failure should not be rendered as builtin failure");
        };
    }
}

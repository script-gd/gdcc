package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.PropertyDef;
import gd.script.gdcc.scope.ScopeOwnerKind;
import gd.script.gdcc.scope.resolver.ScopeMethodResolver;
import gd.script.gdcc.scope.resolver.ScopeResolvedMethod;
import gd.script.gdcc.scope.resolver.ScopePropertyResolver;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Backend adapter for the shared property metadata resolver.
///
/// Backend-specific responsibilities:
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

    enum PropertyAccessorKind {
        READ,
        WRITE
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

    /// Fully resolved exact-engine property accessor material.
    ///
    /// The fixed `index` is part of the wrapper call identity for indexed Godot properties.
    /// Keeping it nullable preserves the distinction between "no index" and `index = 0`.
    record EnginePropertyAccessor(@NotNull ExtensionGdClass propertyOwnerClass,
                                  @NotNull ExtensionGdClass methodOwnerClass,
                                  @NotNull ExtensionGdClass.PropertyInfo property,
                                  @NotNull ExtensionGdClass.ClassMethod method,
                                  @NotNull PropertyAccessorKind kind,
                                  @NotNull GdType propertyType,
                                  @NotNull GdType returnType,
                                  @NotNull List<BackendMethodCallResolver.MethodParamSpec> parameters,
                                  @Nullable Integer index,
                                  @NotNull BackendMethodCallResolver.EngineMethodBindSpec methodBindSpec,
                                  @NotNull String cFunctionName) {
        EnginePropertyAccessor {
            Objects.requireNonNull(propertyOwnerClass);
            Objects.requireNonNull(methodOwnerClass);
            Objects.requireNonNull(property);
            Objects.requireNonNull(method);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(propertyType);
            Objects.requireNonNull(returnType);
            parameters = List.copyOf(parameters);
            Objects.requireNonNull(methodBindSpec);
            Objects.requireNonNull(cFunctionName);
        }

        @NotNull BackendMethodCallResolver.ResolvedMethodCall toResolvedMethodCall() {
            return new BackendMethodCallResolver.ResolvedMethodCall(
                    BackendMethodCallResolver.DispatchMode.ENGINE,
                    method.getName(),
                    methodOwnerClass.getName(),
                    new GdObjectType(methodOwnerClass.getName()),
                    cFunctionName,
                    returnType,
                    parameters,
                    methodBindSpec,
                    method.isVararg(),
                    method.isStatic()
            );
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

    static @NotNull EnginePropertyAccessor resolveEnginePropertyReadAccessor(@NotNull CBodyBuilder bodyBuilder,
                                                                             @NotNull ObjectPropertyLookup lookup,
                                                                             @NotNull String insnName) {
        return resolveEnginePropertyAccessor(bodyBuilder, lookup, insnName, PropertyAccessorKind.READ);
    }

    static @NotNull EnginePropertyAccessor resolveEnginePropertyWriteAccessor(@NotNull CBodyBuilder bodyBuilder,
                                                                              @NotNull ObjectPropertyLookup lookup,
                                                                              @NotNull String insnName) {
        return resolveEnginePropertyAccessor(bodyBuilder, lookup, insnName, PropertyAccessorKind.WRITE);
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

    /// Materializes a validated live typed wrapper pointer for direct GDCC field access.
    /// The `assert_object_live` guard is already present in the LIR stream before this instruction.
    static @NotNull String renderGdccLiveOwnerPointerExpr(@NotNull CBodyBuilder bodyBuilder,
                                                          @NotNull LirVariable ownerVar,
                                                          @NotNull GdObjectType ownerType) {
        var fatType = bodyBuilder.helper().renderObjectFatPtrStorageType(ownerType);
        return fatType + "_live_ptr(" + bodyBuilder.valueOfVar(ownerVar).generateCode() + ")";
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

    private static @NotNull EnginePropertyAccessor resolveEnginePropertyAccessor(@NotNull CBodyBuilder bodyBuilder,
                                                                                 @NotNull ObjectPropertyLookup lookup,
                                                                                 @NotNull String insnName,
                                                                                 @NotNull PropertyAccessorKind kind) {
        var propertyOwnerClass = requireEnginePropertyOwner(bodyBuilder, lookup, insnName);
        var property = requireEngineProperty(bodyBuilder, lookup, propertyOwnerClass, insnName);
        var accessorName = requireAccessorName(bodyBuilder, propertyOwnerClass, property, insnName, kind);
        var propertyType = property.getType();
        var resolved = requireAccessorMethod(
                bodyBuilder,
                propertyOwnerClass,
                property,
                accessorName,
                accessorArgTypes(property, propertyType, kind),
                insnName,
                kind
        );
        var methodOwnerClass = requireEngineMethodOwner(bodyBuilder, propertyOwnerClass, property, resolved, insnName);
        var method = requireEngineMethodMetadata(bodyBuilder, propertyOwnerClass, property, resolved, insnName);
        validateAccessorShape(bodyBuilder, propertyOwnerClass, property, resolved, propertyType, insnName, kind);
        var methodBindSpec = requireMethodBindSpec(bodyBuilder, propertyOwnerClass, property, method, insnName, kind);
        var parameters = BackendMethodCallResolver.toMethodParamSpecs(resolved);
        var cFunctionName = BackendMethodCallResolver.renderEngineMethodCFunctionName(
                methodOwnerClass.getName(),
                resolved.methodName(),
                parameters,
                resolved.returnType(),
                methodBindSpec,
                resolved.isVararg(),
                resolved.isStatic()
        );
        return new EnginePropertyAccessor(
                propertyOwnerClass,
                methodOwnerClass,
                property,
                method,
                kind,
                propertyType,
                resolved.returnType(),
                parameters,
                property.index(),
                methodBindSpec,
                cFunctionName
        );
    }

    private static @NotNull ExtensionGdClass requireEnginePropertyOwner(@NotNull CBodyBuilder bodyBuilder,
                                                                        @NotNull ObjectPropertyLookup lookup,
                                                                        @NotNull String insnName) {
        if (lookup.ownerDispatchMode() != PropertyOwnerDispatchMode.ENGINE ||
                !(lookup.ownerClass() instanceof ExtensionGdClass propertyOwnerClass)) {
            throw bodyBuilder.invalidInsn("Property '" + lookup.property().getName() +
                    "' in " + insnName + " is not owned by an exact engine class");
        }
        return propertyOwnerClass;
    }

    private static @NotNull ExtensionGdClass.PropertyInfo requireEngineProperty(@NotNull CBodyBuilder bodyBuilder,
                                                                                @NotNull ObjectPropertyLookup lookup,
                                                                                @NotNull ExtensionGdClass propertyOwnerClass,
                                                                                @NotNull String insnName) {
        if (!(lookup.property() instanceof ExtensionGdClass.PropertyInfo property)) {
            throw bodyBuilder.invalidInsn("Property '" + lookup.property().getName() +
                    "' on engine class '" + propertyOwnerClass.getName() +
                    "' in " + insnName + " does not carry extension property metadata");
        }
        return property;
    }

    private static @NotNull String requireAccessorName(@NotNull CBodyBuilder bodyBuilder,
                                                       @NotNull ExtensionGdClass ownerClass,
                                                       @NotNull ExtensionGdClass.PropertyInfo property,
                                                       @NotNull String insnName,
                                                       @NotNull PropertyAccessorKind kind) {
        var accessorName = switch (kind) {
            case READ -> property.getGetterFunc();
            case WRITE -> property.getSetterFunc();
        };
        if (accessorName == null || accessorName.isBlank()) {
            throw bodyBuilder.invalidInsn("Engine property '" + ownerClass.getName() + "." +
                    property.getName() + "' has no raw " + accessorLabel(kind) +
                    " accessor in " + insnName);
        }
        return accessorName;
    }

    private static @NotNull List<GdType> accessorArgTypes(@NotNull ExtensionGdClass.PropertyInfo property,
                                                          @NotNull GdType propertyType,
                                                          @NotNull PropertyAccessorKind kind) {
        var indexType = property.index() == null ? List.<GdType>of() : List.<GdType>of(GdIntType.INT);
        return switch (kind) {
            case READ -> indexType;
            case WRITE -> property.index() == null
                    ? List.of(propertyType)
                    : List.of(GdIntType.INT, propertyType);
        };
    }

    private static @NotNull ScopeResolvedMethod requireAccessorMethod(@NotNull CBodyBuilder bodyBuilder,
                                                                      @NotNull ExtensionGdClass ownerClass,
                                                                      @NotNull ExtensionGdClass.PropertyInfo property,
                                                                      @NotNull String accessorName,
                                                                      @NotNull List<GdType> argTypes,
                                                                      @NotNull String insnName,
                                                                      @NotNull PropertyAccessorKind kind) {
        var result = ScopeMethodResolver.resolveInstanceMethod(
                bodyBuilder.classRegistry(),
                new GdObjectType(ownerClass.getName()),
                accessorName,
                argTypes
        );
        return switch (result) {
            case ScopeMethodResolver.Resolved resolved -> resolved.method();
            case ScopeMethodResolver.DynamicFallback dynamicFallback -> throw bodyBuilder.invalidInsn(
                    "Engine property '" + ownerClass.getName() + "." + property.getName() +
                            "' raw " + accessorLabel(kind) + " accessor '" + accessorName +
                            "' could not be resolved in " + insnName + ": " + dynamicFallback.reason()
            );
            case ScopeMethodResolver.Failed failed -> throw bodyBuilder.invalidInsn(
                    "Engine property '" + ownerClass.getName() + "." + property.getName() +
                            "' raw " + accessorLabel(kind) + " accessor '" + accessorName +
                            "' is invalid in " + insnName + ": " + failed.message()
            );
        };
    }

    private static @NotNull ExtensionGdClass requireEngineMethodOwner(@NotNull CBodyBuilder bodyBuilder,
                                                                      @NotNull ExtensionGdClass propertyOwnerClass,
                                                                      @NotNull ExtensionGdClass.PropertyInfo property,
                                                                      @NotNull ScopeResolvedMethod resolved,
                                                                      @NotNull String insnName) {
        if (resolved.ownerKind() != ScopeOwnerKind.ENGINE ||
                !(resolved.ownerClass() instanceof ExtensionGdClass methodOwnerClass)) {
            throw bodyBuilder.invalidInsn("Accessor method '" + resolved.methodName() +
                    "' for engine property '" + propertyOwnerClass.getName() + "." +
                    property.getName() + "' in " + insnName +
                    " resolved to non-engine owner '" + resolved.ownerClass().getName() + "'");
        }
        return methodOwnerClass;
    }

    private static @NotNull ExtensionGdClass.ClassMethod requireEngineMethodMetadata(@NotNull CBodyBuilder bodyBuilder,
                                                                                     @NotNull ExtensionGdClass ownerClass,
                                                                                     @NotNull ExtensionGdClass.PropertyInfo property,
                                                                                     @NotNull ScopeResolvedMethod resolved,
                                                                                     @NotNull String insnName) {
        if (!(resolved.function() instanceof ExtensionGdClass.ClassMethod method)) {
            throw bodyBuilder.invalidInsn("Accessor method '" + resolved.methodName() +
                    "' for engine property '" + ownerClass.getName() + "." +
                    property.getName() + "' in " + insnName +
                    " does not carry extension method metadata");
        }
        return method;
    }

    private static void validateAccessorShape(@NotNull CBodyBuilder bodyBuilder,
                                              @NotNull ExtensionGdClass ownerClass,
                                              @NotNull ExtensionGdClass.PropertyInfo property,
                                              @NotNull ScopeResolvedMethod resolved,
                                              @NotNull GdType propertyType,
                                              @NotNull String insnName,
                                              @NotNull PropertyAccessorKind kind) {
        var expectedCount = switch (kind) {
            case READ -> property.index() == null ? 0 : 1;
            case WRITE -> property.index() == null ? 1 : 2;
        };
        if (resolved.isVararg() || resolved.parameters().size() != expectedCount) {
            throw bodyBuilder.invalidInsn("Engine property '" + ownerClass.getName() + "." +
                    property.getName() + "' raw " + accessorLabel(kind) + " accessor '" +
                    resolved.methodName() + "' has incompatible parameter shape in " + insnName +
                    ": expected " + expectedCount + " fixed parameter(s), got " +
                    resolved.parameters().size());
        }
        if (property.index() != null &&
                !bodyBuilder.classRegistry().checkAssignable(GdIntType.INT, resolved.parameters().getFirst().type())) {
            throw bodyBuilder.invalidInsn("Engine indexed property '" + ownerClass.getName() + "." +
                    property.getName() + "' raw " + accessorLabel(kind) + " accessor '" +
                    resolved.methodName() + "' must accept int-compatible fixed index as the first parameter");
        }
        if (kind == PropertyAccessorKind.WRITE) {
            var valueParameter = resolved.parameters().getLast();
            if (!bodyBuilder.classRegistry().checkAssignable(propertyType, valueParameter.type())) {
                throw bodyBuilder.invalidInsn("Engine property '" + ownerClass.getName() + "." +
                        property.getName() + "' raw setter accessor '" + resolved.methodName() +
                        "' cannot accept property value type '" + propertyType.getTypeName() +
                        "' as parameter type '" + valueParameter.type().getTypeName() + "'");
            }
            if (!(resolved.returnType() instanceof GdVoidType)) {
                throw bodyBuilder.invalidInsn("Engine property '" + ownerClass.getName() + "." +
                        property.getName() + "' raw setter accessor '" + resolved.methodName() +
                        "' must return void, got '" + resolved.returnType().getTypeName() + "'");
            }
            return;
        }
        if (!bodyBuilder.classRegistry().checkAssignable(resolved.returnType(), propertyType)) {
            throw bodyBuilder.invalidInsn("Engine property '" + ownerClass.getName() + "." +
                    property.getName() + "' raw getter accessor '" + resolved.methodName() +
                    "' returns '" + resolved.returnType().getTypeName() +
                    "', which is not assignable to property type '" + propertyType.getTypeName() + "'");
        }
    }

    private static @NotNull BackendMethodCallResolver.EngineMethodBindSpec requireMethodBindSpec(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull ExtensionGdClass ownerClass,
            @NotNull ExtensionGdClass.PropertyInfo property,
            @NotNull ExtensionGdClass.ClassMethod method,
            @NotNull String insnName,
            @NotNull PropertyAccessorKind kind
    ) {
        if (method.hash() == 0L) {
            throw bodyBuilder.invalidInsn("Engine property '" + ownerClass.getName() + "." +
                    property.getName() + "' raw " + accessorLabel(kind) + " accessor '" +
                    method.getName() + "' is missing method-bind hash in extension metadata for " + insnName);
        }
        var hashCompatibility = method.hashCompatibility() == null ? List.<Long>of() : method.hashCompatibility();
        return new BackendMethodCallResolver.EngineMethodBindSpec(method.hash(), hashCompatibility);
    }

    private static @NotNull String accessorLabel(@NotNull PropertyAccessorKind kind) {
        return switch (kind) {
            case READ -> "getter";
            case WRITE -> "setter";
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

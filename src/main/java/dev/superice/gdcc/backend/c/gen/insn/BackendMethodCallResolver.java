package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.gdextension.ExtensionFunctionArgument;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.scope.ScopeOwnerKind;
import dev.superice.gdcc.scope.ParameterDef;
import dev.superice.gdcc.scope.resolver.ScopeDefaultArgKind;
import dev.superice.gdcc.scope.resolver.ScopeMethodParameter;
import dev.superice.gdcc.scope.resolver.ScopeMethodResolver;
import dev.superice.gdcc.scope.resolver.ScopeResolvedMethod;
import dev.superice.gdcc.type.GdNilType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Backend adapter for the shared method resolver.
///
/// - validate backend-only receiver/argument variable shape constraints
/// - translate shared results into legacy `ResolvedMethodCall` records consumed by codegen
/// - preserve the existing `DispatchMode` contract used by `CallMethodInsnGen`
public final class BackendMethodCallResolver {
    private BackendMethodCallResolver() {
    }

    public enum DispatchMode {
        GDCC,
        ENGINE,
        BUILTIN,
        OBJECT_DYNAMIC,
        VARIANT_DYNAMIC
    }

    public enum DefaultArgKind {
        NONE,
        LITERAL,
        FUNCTION
    }

    /// Backend-only per-parameter ABI facts that do not belong in the shared semantic signature.
    public sealed interface ExtraParamSpecData permits BitfieldPassByRefExtraParamSpecData {
    }

    /// Shared semantic resolution intentionally normalizes `bitfield::...` to `int`.
    /// gdextension-lite class-method wrappers still expose the wrapper surface as
    /// `const godot_<Owner>_<Flag> *`, so backend codegen keeps the rendered wrapper CType
    /// as an extra ABI fact on the selected parameter.
    public record BitfieldPassByRefExtraParamSpecData(@NotNull String cType) implements ExtraParamSpecData {
        public BitfieldPassByRefExtraParamSpecData {
            Objects.requireNonNull(cType);
            if (cType.isBlank()) {
                throw new IllegalArgumentException("bitfield pass-by-ref CType must not be blank");
            }
        }
    }

    public record MethodParamSpec(@NotNull String name,
                                  @NotNull GdType type,
                                  @NotNull DefaultArgKind defaultKind,
                                  @Nullable String defaultLiteral,
                                  @Nullable String defaultFunctionName,
                                  @Nullable ExtraParamSpecData extraParamSpecData) {
        public MethodParamSpec {
            Objects.requireNonNull(name);
            Objects.requireNonNull(type);
            Objects.requireNonNull(defaultKind);
            if (defaultKind == DefaultArgKind.LITERAL && (defaultLiteral == null || defaultLiteral.isBlank())) {
                throw new IllegalArgumentException("defaultLiteral must be present when defaultKind is LITERAL");
            }
            if (defaultKind == DefaultArgKind.FUNCTION &&
                    (defaultFunctionName == null || defaultFunctionName.isBlank())) {
                throw new IllegalArgumentException("defaultFunctionName must be present when defaultKind is FUNCTION");
            }
        }

        public boolean hasDefaultValue() {
            return defaultKind != DefaultArgKind.NONE;
        }

        public boolean requiresBitfieldPassByRef() {
            return bitfieldPassByRefExtraParamSpecData() != null;
        }

        public @Nullable BitfieldPassByRefExtraParamSpecData bitfieldPassByRefExtraParamSpecData() {
            return extraParamSpecData instanceof BitfieldPassByRefExtraParamSpecData bitfieldData
                    ? bitfieldData
                    : null;
        }
    }

    public record ResolvedMethodCall(@NotNull DispatchMode mode,
                                     @NotNull String methodName,
                                     @NotNull String ownerClassName,
                                     @NotNull GdType ownerType,
                                     @NotNull String cFunctionName,
                                     @NotNull GdType returnType,
                                     @NotNull List<MethodParamSpec> parameters,
                                     boolean isVararg,
                                     boolean isStatic) {
        public ResolvedMethodCall {
            Objects.requireNonNull(mode);
            Objects.requireNonNull(methodName);
            Objects.requireNonNull(ownerClassName);
            Objects.requireNonNull(ownerType);
            Objects.requireNonNull(cFunctionName);
            Objects.requireNonNull(returnType);
            parameters = List.copyOf(parameters);
        }
    }

    public static @NotNull ResolvedMethodCall resolve(@NotNull CBodyBuilder bodyBuilder,
                                                      @NotNull LirVariable receiverVar,
                                                      @NotNull String methodName,
                                                      @NotNull List<LirVariable> argVars) {
        var receiverType = receiverVar.type();
        if (receiverType instanceof GdVoidType || receiverType instanceof GdNilType) {
            throw bodyBuilder.invalidInsn("Receiver variable '" + receiverVar.id() +
                    "' has invalid type '" + receiverType.getTypeName() + "' for call_method");
        }

        var argTypes = argVars.stream().map(LirVariable::type).toList();
        var result = ScopeMethodResolver.resolveInstanceMethod(
                bodyBuilder.classRegistry(),
                receiverType,
                methodName,
                argTypes
        );
        return switch (result) {
            case ScopeMethodResolver.Resolved resolved -> toResolvedMethodCall(resolved.method());
            case ScopeMethodResolver.DynamicFallback dynamicFallback ->
                    dynamicPlaceholder(toDispatchMode(dynamicFallback.dynamicKind()), receiverType, methodName);
            case ScopeMethodResolver.Failed failed -> throw bodyBuilder.invalidInsn(failed.message());
        };
    }

    private static @NotNull DispatchMode toDispatchMode(@NotNull ScopeMethodResolver.DynamicKind dynamicKind) {
        return switch (dynamicKind) {
            case OBJECT_DYNAMIC -> DispatchMode.OBJECT_DYNAMIC;
            case VARIANT_DYNAMIC -> DispatchMode.VARIANT_DYNAMIC;
        };
    }

    private static @NotNull DispatchMode toDispatchMode(@NotNull ScopeOwnerKind ownerKind) {
        return switch (ownerKind) {
            case GDCC -> DispatchMode.GDCC;
            case ENGINE -> DispatchMode.ENGINE;
            case BUILTIN -> DispatchMode.BUILTIN;
        };
    }

    private static @NotNull ResolvedMethodCall dynamicPlaceholder(@NotNull DispatchMode mode,
                                                                  @NotNull GdType receiverType,
                                                                  @NotNull String methodName) {
        return new ResolvedMethodCall(
                mode,
                methodName,
                receiverType.getTypeName(),
                receiverType,
                "",
                GdVariantType.VARIANT,
                List.of(),
                true,
                false
        );
    }

    private static @NotNull ResolvedMethodCall toResolvedMethodCall(@NotNull ScopeResolvedMethod resolved) {
        var mode = toDispatchMode(resolved.ownerKind());
        var ownerClassName = resolved.ownerClass().getName();
        var sourceParameters = resolved.function().getParameters();
        var parameters = new java.util.ArrayList<MethodParamSpec>(resolved.parameters().size());
        for (var i = 0; i < resolved.parameters().size(); i++) {
            var sourceParameter = isExtensionMethodMetadata(resolved)
                    ? sourceParameters.get(i)
                    : null;
            parameters.add(toMethodParamSpec(resolved.parameters().get(i), sourceParameter));
        }
        return new ResolvedMethodCall(
                mode,
                resolved.methodName(),
                ownerClassName,
                resolved.ownerType(),
                renderMethodCFunctionName(mode, ownerClassName, resolved.methodName()),
                resolved.returnType(),
                parameters,
                resolved.isVararg(),
                resolved.isStatic()
        );
    }

    private static boolean isExtensionMethodMetadata(@NotNull ScopeResolvedMethod resolved) {
        return resolved.function() instanceof dev.superice.gdcc.gdextension.ExtensionBuiltinClass.ClassMethod
                || resolved.function() instanceof dev.superice.gdcc.gdextension.ExtensionGdClass.ClassMethod;
    }

    private static @NotNull MethodParamSpec toMethodParamSpec(@NotNull ScopeMethodParameter parameter,
                                                              @Nullable ParameterDef sourceParameter) {
        return new MethodParamSpec(
                parameter.name(),
                parameter.type(),
                toDefaultArgKind(parameter.defaultArgKind()),
                parameter.defaultLiteral(),
                parameter.defaultFunctionName(),
                resolveExtraParamSpecData(sourceParameter)
        );
    }

    /// Shared semantic resolution keeps the published parameter type normalized.
    /// Backend-only ABI quirks stay here so the shared resolver does not need to care about
    /// wrapper-surface details from gdextension-lite.
    private static @Nullable ExtraParamSpecData resolveExtraParamSpecData(@Nullable ParameterDef sourceParameter) {
        if (!(sourceParameter instanceof ExtensionFunctionArgument extensionArgument)) {
            return null;
        }
        var rawType = extensionArgument.type();
        if (rawType == null || !rawType.startsWith("bitfield::")) {
            return null;
        }
        var leafName = rawType.substring("bitfield::".length()).trim();
        if (leafName.isBlank()) {
            throw new IllegalArgumentException("Malformed bitfield metadata type: " + rawType);
        }
        return new BitfieldPassByRefExtraParamSpecData("godot_" + leafName.replace('.', '_'));
    }

    private static @NotNull DefaultArgKind toDefaultArgKind(@NotNull ScopeDefaultArgKind defaultArgKind) {
        return switch (defaultArgKind) {
            case NONE -> DefaultArgKind.NONE;
            case LITERAL -> DefaultArgKind.LITERAL;
            case FUNCTION -> DefaultArgKind.FUNCTION;
        };
    }

    private static @NotNull String renderMethodCFunctionName(@NotNull DispatchMode mode,
                                                             @NotNull String ownerClassName,
                                                             @NotNull String methodName) {
        return switch (mode) {
            case GDCC -> ownerClassName + "_" + methodName;
            case ENGINE, BUILTIN -> "godot_" + ownerClassName + "_" + methodName;
            default -> "";
        };
    }
}

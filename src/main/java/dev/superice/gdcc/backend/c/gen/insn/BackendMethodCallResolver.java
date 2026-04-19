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
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdPrimitiveType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
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

    /// Exact-engine helpers use one normalized callable surface and let the generated helper decide
    /// how ptrcall should see each fixed argument.
    public enum EngineHelperSlotMode {
        /// The helper parameter is the normalized value itself, and ptrcall receives `&arg`.
        VALUE_ADDRESS,
        /// The helper parameter already points at stable storage, so ptrcall receives `arg` directly.
        STORAGE_POINTER,
        /// The helper parameter stays normalized, but the helper must first materialize a raw leaf slot.
        LOCAL_VALUE_SLOT_ADDRESS
    }

    /// Backend-only per-parameter ABI facts that do not belong in the shared semantic signature.
    public sealed interface ExtraParamSpecData permits EngineHelperSlotExtraParamSpecData {
    }

    /// Shared semantic resolution keeps enum/bitfield parameters normalized as `int`.
    /// Exact-engine helpers still need the raw exported leaf CType so they can materialize the
    /// ptrcall slot locally without pushing wrapper-compatible casts back into Java callers.
    public record EngineHelperSlotExtraParamSpecData(@NotNull EngineHelperSlotMode slotMode,
                                                     @Nullable String slotCType) implements ExtraParamSpecData {
        public EngineHelperSlotExtraParamSpecData {
            Objects.requireNonNull(slotMode);
            if (slotMode == EngineHelperSlotMode.LOCAL_VALUE_SLOT_ADDRESS &&
                    (slotCType == null || slotCType.isBlank())) {
                throw new IllegalArgumentException("slotCType must be present for LOCAL_VALUE_SLOT_ADDRESS");
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

        public @NotNull EngineHelperSlotMode engineHelperSlotMode() {
            var slotExtraData = engineHelperSlotExtraParamSpecData();
            if (slotExtraData != null) {
                return slotExtraData.slotMode();
            }
            return type instanceof GdPrimitiveType || type instanceof GdObjectType
                    ? EngineHelperSlotMode.VALUE_ADDRESS
                    : EngineHelperSlotMode.STORAGE_POINTER;
        }

        public boolean requiresEngineHelperLocalValueSlot() {
            return engineHelperSlotMode() == EngineHelperSlotMode.LOCAL_VALUE_SLOT_ADDRESS;
        }

        public @Nullable String engineHelperLocalSlotCType() {
            var slotExtraData = engineHelperSlotExtraParamSpecData();
            return slotExtraData == null ? null : slotExtraData.slotCType();
        }

        public @Nullable EngineHelperSlotExtraParamSpecData engineHelperSlotExtraParamSpecData() {
            return extraParamSpecData instanceof EngineHelperSlotExtraParamSpecData slotExtraData
                    ? slotExtraData
                    : null;
        }
    }

    /// Backend-only bind lookup identity for exact engine methods.
    /// Shared semantic resolution stays normalized and does not carry these direct-bind facts.
    public record EngineMethodBindSpec(long hash, @NotNull List<Long> hashCompatibility) {
        public EngineMethodBindSpec {
            if (hash == 0L) {
                throw new IllegalArgumentException("engine method bind hash must not be zero");
            }
            hashCompatibility = List.copyOf(hashCompatibility);
        }
    }

    public record ResolvedMethodCall(@NotNull DispatchMode mode,
                                     @NotNull String methodName,
                                     @NotNull String ownerClassName,
                                     @NotNull GdType ownerType,
                                     @NotNull String cFunctionName,
                                     @NotNull GdType returnType,
                                     @NotNull List<MethodParamSpec> parameters,
                                     @Nullable EngineMethodBindSpec engineMethodBindSpec,
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
            case ScopeMethodResolver.Resolved resolved -> toResolvedMethodCall(bodyBuilder, resolved.method());
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
                null,
                true,
                false
        );
    }

    private static @NotNull ResolvedMethodCall toResolvedMethodCall(@NotNull CBodyBuilder bodyBuilder,
                                                                    @NotNull ScopeResolvedMethod resolved) {
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
        var engineMethodBindSpec = resolveEngineMethodBindSpec(bodyBuilder, resolved, mode);
        return new ResolvedMethodCall(
                mode,
                resolved.methodName(),
                ownerClassName,
                resolved.ownerType(),
                renderMethodCFunctionName(
                        mode,
                        ownerClassName,
                        resolved.methodName(),
                        engineMethodBindSpec,
                        resolved.isVararg(),
                        resolved.isStatic()
                ),
                resolved.returnType(),
                parameters,
                engineMethodBindSpec,
                resolved.isVararg(),
                resolved.isStatic()
        );
    }

    /// Only exact engine class metadata contributes method-bind identity.
    /// A non-zero primary hash is required for every exact engine route.
    private static @Nullable EngineMethodBindSpec resolveEngineMethodBindSpec(@NotNull CBodyBuilder bodyBuilder,
                                                                              @NotNull ScopeResolvedMethod resolved,
                                                                              @NotNull DispatchMode mode) {
        if (mode != DispatchMode.ENGINE) {
            return null;
        }
        return switch (resolved.function()) {
            case dev.superice.gdcc.gdextension.ExtensionGdClass.ClassMethod method when method.hash() != 0L ->
                    new EngineMethodBindSpec(method.hash(), normalizeHashCompatibility(method.hashCompatibility()));
            case dev.superice.gdcc.gdextension.ExtensionGdClass.ClassMethod _ -> throw bodyBuilder.invalidInsn(
                    "Exact engine method '" + resolved.ownerClass().getName() + "." + resolved.methodName() +
                            "' is missing method-bind hash in extension metadata"
            );
            default -> null;
        };
    }

    private static @NotNull List<Long> normalizeHashCompatibility(@Nullable List<Long> hashCompatibility) {
        return hashCompatibility == null ? List.of() : hashCompatibility;
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
    /// Backend-only helper slot facts stay here so the shared resolver does not need to care about
    /// raw ptrcall slot spellings exported by extension metadata.
    private static @Nullable ExtraParamSpecData resolveExtraParamSpecData(@Nullable ParameterDef sourceParameter) {
        if (!(sourceParameter instanceof ExtensionFunctionArgument extensionArgument)) {
            return null;
        }
        var rawType = extensionArgument.type();
        if (rawType == null || rawType.isBlank()) {
            return null;
        }
        return switch (detectEngineHelperLocalSlotMetadataFamily(rawType)) {
            case NONE -> null;
            case ENUM, BITFIELD -> new EngineHelperSlotExtraParamSpecData(
                    EngineHelperSlotMode.LOCAL_VALUE_SLOT_ADDRESS,
                    renderEngineHelperLocalSlotCType(rawType)
            );
        };
    }

    private static @NotNull EngineHelperLocalSlotMetadataFamily detectEngineHelperLocalSlotMetadataFamily(
            @NotNull String rawType
    ) {
        var normalized = rawType.trim();
        if (normalized.startsWith("enum::")) {
            return EngineHelperLocalSlotMetadataFamily.ENUM;
        }
        if (normalized.startsWith("bitfield::")) {
            return EngineHelperLocalSlotMetadataFamily.BITFIELD;
        }
        return EngineHelperLocalSlotMetadataFamily.NONE;
    }

    private static @NotNull String renderEngineHelperLocalSlotCType(@NotNull String rawType) {
        var normalized = rawType.trim();
        var family = detectEngineHelperLocalSlotMetadataFamily(normalized);
        if (family == EngineHelperLocalSlotMetadataFamily.NONE) {
            throw new IllegalArgumentException("Unsupported engine helper local-slot metadata: " + rawType);
        }
        var leafName = normalized.substring(normalized.indexOf("::") + 2).trim();
        if (leafName.isBlank()) {
            throw new IllegalArgumentException("Malformed engine helper local-slot metadata type: " + rawType);
        }
        return "godot_" + leafName.replace('.', '_');
    }

    private enum EngineHelperLocalSlotMetadataFamily {
        NONE,
        ENUM,
        BITFIELD
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
                                                             @NotNull String methodName,
                                                             @Nullable EngineMethodBindSpec engineMethodBindSpec,
                                                             boolean isVararg,
                                                             boolean isStatic) {
        return switch (mode) {
            case GDCC -> ownerClassName + "_" + methodName;
            case ENGINE -> renderEngineMethodCFunctionName(
                    ownerClassName,
                    methodName,
                    engineMethodBindSpec,
                    isVararg,
                    isStatic
            );
            case BUILTIN -> "godot_" + ownerClassName + "_" + methodName;
            default -> "";
        };
    }

    /// Exact engine helper naming assumes bind lookup identity has already been validated.
    private static @NotNull String renderEngineMethodCFunctionName(@NotNull String ownerClassName,
                                                                   @NotNull String methodName,
                                                                   @Nullable EngineMethodBindSpec engineMethodBindSpec,
                                                                   boolean isVararg,
                                                                   boolean isStatic) {
        var bindSpec = Objects.requireNonNull(engineMethodBindSpec, "Exact engine route requires method bind spec");
        var name = new StringBuilder(isVararg ? "gdcc_engine_callv_" : "gdcc_engine_call_");
        if (isStatic) {
            name.append("static_");
        }
        name.append(sanitizeHelperNameFragment(ownerClassName))
                .append("_")
                .append(sanitizeHelperNameFragment(methodName))
                .append("_")
                .append(bindSpec.hash());
        return name.toString();
    }

    private static @NotNull String sanitizeHelperNameFragment(@NotNull String raw) {
        var normalized = raw.toLowerCase(Locale.ROOT);
        var sanitized = normalized.replaceAll("[^a-z0-9_]+", "_").replaceAll("_+", "_");
        if (sanitized.startsWith("_")) {
            sanitized = sanitized.substring(1);
        }
        if (sanitized.endsWith("_")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        if (sanitized.isBlank()) {
            throw new IllegalArgumentException("Identifier fragment becomes empty after sanitization: " + raw);
        }
        return sanitized;
    }
}

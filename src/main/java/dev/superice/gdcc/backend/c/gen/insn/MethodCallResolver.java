package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.gdextension.ExtensionFunctionArgument;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.scope.ClassDef;
import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.scope.ParameterDef;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdNilType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdPackedArrayType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/// Shared resolver for `call_method` dispatch mode and method signature lookup.
///
/// Phase 1 scope:
/// - Resolve static/direct call metadata for GDCC / ENGINE / BUILTIN receivers.
/// - Fall back to OBJECT_DYNAMIC when object receiver cannot be resolved statically.
/// - Return OBJECT_DYNAMIC / VARIANT_DYNAMIC modes for runtime dynamic paths.
public final class MethodCallResolver {
    private MethodCallResolver() {
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

    public record MethodParamSpec(@NotNull String name,
                                  @NotNull GdType type,
                                  @NotNull DefaultArgKind defaultKind,
                                  @Nullable String defaultLiteral,
                                  @Nullable String defaultFunctionName) {
        public MethodParamSpec {
            Objects.requireNonNull(name);
            Objects.requireNonNull(type);
            Objects.requireNonNull(defaultKind);
            if (defaultKind == DefaultArgKind.LITERAL && (defaultLiteral == null || defaultLiteral.isBlank())) {
                throw new IllegalArgumentException("defaultLiteral must be present when defaultKind is LITERAL");
            }
            if (defaultKind == DefaultArgKind.FUNCTION && (defaultFunctionName == null || defaultFunctionName.isBlank())) {
                throw new IllegalArgumentException("defaultFunctionName must be present when defaultKind is FUNCTION");
            }
        }

        public boolean hasDefaultValue() {
            return defaultKind != DefaultArgKind.NONE;
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

    private record MethodCandidate(@NotNull ResolvedMethodCall resolved, int ownerDistance) {
        private MethodCandidate {
            Objects.requireNonNull(resolved);
            if (ownerDistance < 0) {
                throw new IllegalArgumentException("ownerDistance must be >= 0");
            }
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

        if (receiverType instanceof GdObjectType objectType) {
            var resolvedKnown = tryResolveKnownObjectMethod(bodyBuilder, objectType, methodName, argVars);
            return Objects.requireNonNullElseGet(resolvedKnown, () -> dynamicPlaceholder(DispatchMode.OBJECT_DYNAMIC, receiverType, methodName));
        }

        if (receiverType instanceof GdVariantType) {
            return dynamicPlaceholder(DispatchMode.VARIANT_DYNAMIC, receiverType, methodName);
        }
        return resolveBuiltinMethod(bodyBuilder, receiverType, methodName, argVars);
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

    private static @Nullable ResolvedMethodCall tryResolveKnownObjectMethod(@NotNull CBodyBuilder bodyBuilder,
                                                                            @NotNull GdObjectType receiverType,
                                                                            @NotNull String methodName,
                                                                            @NotNull List<LirVariable> argVars) {
        var classDef = bodyBuilder.classRegistry().getClassDef(receiverType);
        if (classDef == null) {
            return null;
        }

        var candidates = collectMethodCandidates(bodyBuilder, classDef, methodName);
        if (candidates.isEmpty()) {
            return null;
        }
        var selected = chooseBestCandidate(bodyBuilder, methodName, receiverType, candidates, argVars, true);
        return selected == null ? null : selected.resolved();
    }

    private static @NotNull ResolvedMethodCall resolveBuiltinMethod(@NotNull CBodyBuilder bodyBuilder,
                                                                    @NotNull GdType receiverType,
                                                                    @NotNull String methodName,
                                                                    @NotNull List<LirVariable> argVars) {
        var lookupName = normalizeBuiltinReceiverLookupName(receiverType);
        var builtinClass = bodyBuilder.classRegistry().findBuiltinClass(lookupName);
        if (builtinClass == null) {
            throw bodyBuilder.invalidInsn("Builtin class '" + receiverType.getTypeName() +
                    "' not found for call_method receiver (lookup key: '" + lookupName + "')");
        }
        var candidates = collectMethodCandidates(bodyBuilder, builtinClass, methodName);
        if (candidates.isEmpty()) {
            throw bodyBuilder.invalidInsn("Method '" + methodName + "' not found in builtin class '" +
                    receiverType.getTypeName() + "'");
        }
        var selected = chooseBestCandidate(bodyBuilder, methodName, receiverType, candidates, argVars, false);
        if (selected == null) {
            throw bodyBuilder.invalidInsn("Ambiguous overload for method '" + methodName + "' on type '" +
                    receiverType.getTypeName() + "'");
        }
        return selected.resolved();
    }

    private static @NotNull List<MethodCandidate> collectMethodCandidates(@NotNull CBodyBuilder bodyBuilder,
                                                                          @NotNull ClassDef startClass,
                                                                          @NotNull String methodName) {
        var registry = bodyBuilder.classRegistry();
        var out = new ArrayList<MethodCandidate>();
        ClassDef current = startClass;
        var visited = new HashSet<String>();
        var ownerDistance = 0;
        while (current != null) {
            if (!visited.add(current.getName())) {
                break;
            }
            var ownerMode = resolveOwnerDispatchMode(bodyBuilder, current);
            for (var function : current.getFunctions()) {
                if (!function.getName().equals(methodName)) {
                    continue;
                }
                var resolved = toResolvedMethodCall(bodyBuilder, ownerMode, current, function);
                out.add(new MethodCandidate(resolved, ownerDistance));
            }
            var superName = current.getSuperName();
            if (superName.isBlank()) {
                break;
            }
            current = registry.getClassDef(new GdObjectType(superName));
            ownerDistance++;
        }
        return out;
    }

    /// Object receiver dynamic fallback:
    /// - If no applicable candidate: still fail-fast (invalid arguments).
    /// - If ambiguous between equally-best candidates and `fallbackToDynamicOnAmbiguous=true`,
    ///   return null so caller can emit OBJECT_DYNAMIC.
    /// - Otherwise, report ambiguous overload as compile-time error.
    private static @Nullable MethodCandidate chooseBestCandidate(@NotNull CBodyBuilder bodyBuilder,
                                                                 @NotNull String methodName,
                                                                 @NotNull GdType receiverType,
                                                                 @NotNull List<MethodCandidate> candidates,
                                                                 @NotNull List<LirVariable> argVars,
                                                                 boolean fallbackToDynamicOnAmbiguous) {
        var applicable = new ArrayList<MethodCandidate>();
        for (var candidate : candidates) {
            if (matchesArguments(bodyBuilder, candidate.resolved(), argVars)) {
                applicable.add(candidate);
            }
        }
        if (applicable.isEmpty()) {
            var preferred = choosePreferredCandidate(candidates);
            var mismatchReason = buildMismatchReason(bodyBuilder, preferred.resolved(), argVars);
            throw bodyBuilder.invalidInsn("No applicable overload for method '" + methodName + "' on type '" +
                    receiverType.getTypeName() + "': " + mismatchReason + ". candidates: " + renderCandidates(candidates));
        }

        var minOwnerDistance = applicable.stream().mapToInt(MethodCandidate::ownerDistance).min().orElse(0);
        var ownerPool = applicable.stream().filter(c -> c.ownerDistance() == minOwnerDistance).toList();

        var nonStaticApplicable = ownerPool.stream().filter(c -> !c.resolved().isStatic()).toList();
        var pool = nonStaticApplicable.isEmpty() ? ownerPool : nonStaticApplicable;

        var nonVarargPool = pool.stream().filter(c -> !c.resolved().isVararg()).toList();
        pool = nonVarargPool.isEmpty() ? pool : nonVarargPool;

        if (pool.size() == 1) {
            return pool.getFirst();
        }
        if (fallbackToDynamicOnAmbiguous) {
            return null;
        }
        throw bodyBuilder.invalidInsn("Ambiguous overload for method '" + methodName + "' on type '" +
                receiverType.getTypeName() + "': " + renderCandidates(pool));
    }

    private static @NotNull MethodCandidate choosePreferredCandidate(@NotNull List<MethodCandidate> candidates) {
        var minOwnerDistance = candidates.stream().mapToInt(MethodCandidate::ownerDistance).min().orElse(0);
        var ownerPool = candidates.stream().filter(c -> c.ownerDistance() == minOwnerDistance).toList();

        var nonStaticApplicable = ownerPool.stream().filter(c -> !c.resolved().isStatic()).toList();
        var pool = nonStaticApplicable.isEmpty() ? ownerPool : nonStaticApplicable;

        var nonVarargPool = pool.stream().filter(c -> !c.resolved().isVararg()).toList();
        pool = nonVarargPool.isEmpty() ? pool : nonVarargPool;
        return pool.getFirst();
    }

    private static @NotNull String buildMismatchReason(@NotNull CBodyBuilder bodyBuilder,
                                                       @NotNull ResolvedMethodCall candidate,
                                                       @NotNull List<LirVariable> argVars) {
        var fixedCount = candidate.parameters().size();
        var providedCount = argVars.size();
        if (providedCount < fixedCount && !canOmitTrailingParameters(candidate.parameters(), providedCount)) {
            var missingParam = firstMissingRequiredParameter(candidate.parameters(), providedCount);
            return "Too few arguments for method '" + candidate.ownerClassName() + "." + candidate.methodName() +
                    "': missing required parameter #" + (missingParam + 1) + " ('" +
                    candidate.parameters().get(missingParam).name() + "')";
        }
        if (!candidate.isVararg() && providedCount > fixedCount) {
            return "Too many arguments for method '" + candidate.ownerClassName() + "." + candidate.methodName() +
                    "': expected " + fixedCount + ", got " + providedCount;
        }
        var providedFixedCount = Math.min(providedCount, fixedCount);
        for (var i = 0; i < providedFixedCount; i++) {
            var argType = argVars.get(i).type();
            var param = candidate.parameters().get(i);
            if (!bodyBuilder.classRegistry().checkAssignable(argType, param.type())) {
                return "Cannot assign value of type '" + argType.getTypeName() +
                        "' to method parameter #" + (i + 1) + " ('" + param.name() +
                        "') of type '" + param.type().getTypeName() + "'";
            }
        }
        if (candidate.isVararg()) {
            for (var i = fixedCount; i < providedCount; i++) {
                var argType = argVars.get(i).type();
                if (!bodyBuilder.classRegistry().checkAssignable(argType, GdVariantType.VARIANT)) {
                    return "Vararg argument #" + (i + 1) + " of method '" +
                            candidate.ownerClassName() + "." + candidate.methodName() +
                            "' must be Variant, got '" + argType.getTypeName() + "'";
                }
            }
        }
        return "no compatible signature found";
    }

    private static @NotNull String renderCandidates(@NotNull List<MethodCandidate> candidates) {
        var joiner = new StringJoiner("; ");
        for (var candidate : candidates) {
            var resolved = candidate.resolved();
            joiner.add(renderSignature(resolved) + " [distance=" + candidate.ownerDistance() + "]");
        }
        return joiner.toString();
    }

    private static @NotNull String renderSignature(@NotNull ResolvedMethodCall resolved) {
        var argsJoiner = new StringJoiner(", ");
        for (var parameter : resolved.parameters()) {
            argsJoiner.add(parameter.type().getTypeName());
        }
        if (resolved.isVararg()) {
            argsJoiner.add("...");
        }
        var staticPrefix = resolved.isStatic() ? "static " : "";
        return staticPrefix + resolved.ownerClassName() + "." + resolved.methodName() + "(" + argsJoiner + ")";
    }

    private static boolean matchesArguments(@NotNull CBodyBuilder bodyBuilder,
                                            @NotNull ResolvedMethodCall candidate,
                                            @NotNull List<LirVariable> argVars) {
        var fixedCount = candidate.parameters().size();
        var provided = argVars.size();
        if (provided < fixedCount && !canOmitTrailingParameters(candidate.parameters(), provided)) {
            return false;
        }
        if (!candidate.isVararg() && provided > fixedCount) {
            return false;
        }
        var providedFixedCount = Math.min(provided, fixedCount);
        for (var i = 0; i < providedFixedCount; i++) {
            var argType = argVars.get(i).type();
            var paramType = candidate.parameters().get(i).type();
            if (!bodyBuilder.classRegistry().checkAssignable(argType, paramType)) {
                return false;
            }
        }
        if (!candidate.isVararg()) {
            return true;
        }
        for (var i = fixedCount; i < provided; i++) {
            if (!bodyBuilder.classRegistry().checkAssignable(argVars.get(i).type(), GdVariantType.VARIANT)) {
                return false;
            }
        }
        return true;
    }

    private static boolean canOmitTrailingParameters(@NotNull List<MethodParamSpec> parameters,
                                                     int providedCount) {
        for (var i = providedCount; i < parameters.size(); i++) {
            if (!parameters.get(i).hasDefaultValue()) {
                return false;
            }
        }
        return true;
    }

    private static int firstMissingRequiredParameter(@NotNull List<MethodParamSpec> parameters,
                                                     int providedCount) {
        for (var i = providedCount; i < parameters.size(); i++) {
            if (!parameters.get(i).hasDefaultValue()) {
                return i;
            }
        }
        return providedCount;
    }

    private static @NotNull DispatchMode resolveOwnerDispatchMode(@NotNull CBodyBuilder bodyBuilder,
                                                                  @NotNull ClassDef ownerClass) {
        var ownerClassName = ownerClass.getName();
        var registry = bodyBuilder.classRegistry();
        if (registry.isGdccClass(ownerClassName)) {
            return DispatchMode.GDCC;
        }
        if (registry.isGdClass(ownerClassName)) {
            return DispatchMode.ENGINE;
        }
        if (registry.isBuiltinClass(ownerClassName)) {
            return DispatchMode.BUILTIN;
        }
        throw bodyBuilder.invalidInsn("Unsupported method owner '" + ownerClassName +
                "': unable to classify as GDCC/ENGINE/BUILTIN");
    }

    private static @NotNull String normalizeBuiltinReceiverLookupName(@NotNull GdType receiverType) {
        return switch (receiverType) {
            case GdArrayType _ -> "Array";
            case GdDictionaryType _ -> "Dictionary";
            default -> receiverType.getTypeName();
        };
    }

    private static @NotNull ResolvedMethodCall toResolvedMethodCall(@NotNull CBodyBuilder bodyBuilder,
                                                                    @NotNull DispatchMode mode,
                                                                    @NotNull ClassDef ownerClass,
                                                                    @NotNull FunctionDef function) {
        var params = new ArrayList<MethodParamSpec>();
        var startParamIndex = 0;
        if (mode == DispatchMode.GDCC && !function.isStatic()) {
            var firstParam = function.getParameter(0);
            if (firstParam == null || !firstParam.getName().equals("self")) {
                throw bodyBuilder.invalidInsn("GDCC method '" + ownerClass.getName() + "." + function.getName() +
                        "' must have 'self' as its first parameter");
            }
            startParamIndex = 1;
        }
        for (var i = startParamIndex; i < function.getParameterCount(); i++) {
            var parameter = function.getParameter(i);
            if (parameter == null) {
                throw bodyBuilder.invalidInsn("Method parameter #" + (i + 1) + " metadata is missing for '" +
                        ownerClass.getName() + "." + function.getName() + "'");
            }
            var parameterType = resolveMethodParameterType(bodyBuilder, ownerClass, function, parameter, i + 1);
            var defaultKind = resolveDefaultArgKind(parameter);
            var defaultLiteral = defaultKind == DefaultArgKind.LITERAL
                    ? ((ExtensionFunctionArgument) parameter).defaultValue()
                    : null;
            var defaultFunctionName = defaultKind == DefaultArgKind.FUNCTION
                    ? parameter.getDefaultValueFunc()
                    : null;
            params.add(new MethodParamSpec(
                    parameter.getName(),
                    parameterType,
                    defaultKind,
                    defaultLiteral,
                    defaultFunctionName
            ));
        }

        var ownerType = resolveOwnerType(bodyBuilder, mode, ownerClass.getName());
        var cFunctionName = renderMethodCFunctionName(mode, ownerClass.getName(), function.getName());
        var returnType = resolveMethodReturnType(bodyBuilder, ownerClass, function);
        return new ResolvedMethodCall(
                mode,
                function.getName(),
                ownerClass.getName(),
                ownerType,
                cFunctionName,
                returnType,
                params,
                function.isVararg(),
                function.isStatic()
        );
    }

    private static @NotNull DefaultArgKind resolveDefaultArgKind(@NotNull ParameterDef parameter) {
        if (parameter instanceof ExtensionFunctionArgument extensionArgument &&
                extensionArgument.defaultValue() != null && !extensionArgument.defaultValue().isBlank()) {
            return DefaultArgKind.LITERAL;
        }
        var defaultValueFunc = parameter.getDefaultValueFunc();
        if (defaultValueFunc != null && !defaultValueFunc.isBlank()) {
            return DefaultArgKind.FUNCTION;
        }
        return DefaultArgKind.NONE;
    }

    private static @NotNull GdType resolveMethodParameterType(@NotNull CBodyBuilder bodyBuilder,
                                                              @NotNull ClassDef ownerClass,
                                                              @NotNull FunctionDef function,
                                                              @NotNull ParameterDef parameter,
                                                              int parameterIndexBaseOne) {
        if (parameter instanceof ExtensionFunctionArgument extensionArgument) {
            return parseExtensionType(
                    bodyBuilder,
                    extensionArgument.type(),
                    "method parameter #" + parameterIndexBaseOne + " of '" +
                            ownerClass.getName() + "." + function.getName() + "'"
            );
        }
        return parameter.getType();
    }

    private static @NotNull GdType resolveMethodReturnType(@NotNull CBodyBuilder bodyBuilder,
                                                           @NotNull ClassDef ownerClass,
                                                           @NotNull FunctionDef function) {
        if (function instanceof ExtensionBuiltinClass.ClassMethod builtinMethod) {
            var rawReturnType = builtinMethod.returnValue() != null
                    ? builtinMethod.returnValue().type()
                    : builtinMethod.returnType();
            return parseExtensionType(
                    bodyBuilder,
                    rawReturnType,
                    "return type of '" + ownerClass.getName() + "." + function.getName() + "'"
            );
        }
        if (function instanceof ExtensionGdClass.ClassMethod gdMethod) {
            var returnInfo = gdMethod.returnValue();
            var rawReturnType = returnInfo == null ? "void" : returnInfo.type();
            return parseExtensionType(
                    bodyBuilder,
                    rawReturnType,
                    "return type of '" + ownerClass.getName() + "." + function.getName() + "'"
            );
        }
        return function.getReturnType();
    }

    private static @NotNull GdType parseExtensionType(@NotNull CBodyBuilder bodyBuilder,
                                                      @Nullable String rawTypeName,
                                                      @NotNull String typeUseSite) {
        if (rawTypeName == null || rawTypeName.isBlank()) {
            return GdVoidType.VOID;
        }
        var normalized = rawTypeName.trim();
        if (normalized.startsWith("enum::") || normalized.startsWith("bitfield::")) {
            return GdIntType.INT;
        }
        if (normalized.startsWith("typedarray::")) {
            var elementTypeName = normalized.substring("typedarray::".length()).trim();
            if (elementTypeName.isBlank()) {
                throw bodyBuilder.invalidInsn(typeUseSite + " has malformed typedarray metadata: '" + rawTypeName + "'");
            }
            var elementType = parseExtensionType(bodyBuilder, elementTypeName, typeUseSite);
            if (elementType instanceof GdPackedArrayType) {
                return elementType;
            }
            return new GdArrayType(elementType);
        }
        var parsed = dev.superice.gdcc.scope.ClassRegistry.tryParseTextType(normalized);
        if (parsed == null) {
            throw bodyBuilder.invalidInsn(typeUseSite + " has unsupported type metadata: '" + rawTypeName + "'");
        }
        return parsed;
    }

    private static @NotNull GdType resolveOwnerType(@NotNull CBodyBuilder bodyBuilder,
                                                    @NotNull DispatchMode mode,
                                                    @NotNull String ownerClassName) {
        return switch (mode) {
            case GDCC, ENGINE -> new GdObjectType(ownerClassName);
            case BUILTIN -> {
                var parsed = dev.superice.gdcc.scope.ClassRegistry.tryParseTextType(ownerClassName);
                if (parsed == null) {
                    throw bodyBuilder.invalidInsn("Unsupported builtin owner type '" + ownerClassName + "'");
                }
                yield parsed;
            }
            default -> throw bodyBuilder.invalidInsn("Unsupported direct call dispatch mode: " + mode);
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

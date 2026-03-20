package dev.superice.gdcc.scope.resolver;

import dev.superice.gdcc.exception.ScopeMethodResolutionException;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.scope.ClassDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.scope.ParameterDef;
import dev.superice.gdcc.scope.ScopeOwnerKind;
import dev.superice.gdcc.scope.ScopeTypeMeta;
import dev.superice.gdcc.scope.ScopeTypeMetaKind;
import dev.superice.gdcc.gdextension.ExtensionFunctionArgument;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/// Shared method metadata resolver for frontend/backend.
///
/// Boundaries:
/// - supports instance receivers (`GdType`) and static/type-meta receivers (`ScopeTypeMeta`)
/// - centralizes candidate collection, applicability checks, and ranking rules
/// - does not decide frontend lowering shape or backend C emission details
/// - does not treat constructors (`ClassName.new(...)` / `_init`) as ordinary methods
public final class ScopeMethodResolver {
    private ScopeMethodResolver() {
    }

    /// Runtime-dynamic route that the caller may still choose after static lookup stops.
    public enum DynamicKind {
        OBJECT_DYNAMIC,
        VARIANT_DYNAMIC
    }

    /// Why the resolver allowed a dynamic fallback instead of returning a unique candidate.
    public enum DynamicFallbackReason {
        RECEIVER_METADATA_UNKNOWN,
        METHOD_MISSING,
        AMBIGUOUS_OVERLOAD,
        VARIANT_RECEIVER
    }

    /// Stable hard-failure categories.
    public enum FailureKind {
        BUILTIN_CLASS_NOT_FOUND,
        METHOD_NOT_FOUND,
        NO_APPLICABLE_OVERLOAD,
        AMBIGUOUS_OVERLOAD,
        UNSUPPORTED_OWNER,
        MALFORMED_METADATA,
        UNSUPPORTED_STATIC_RECEIVER,
        CONSTRUCTOR_ROUTE_UNSUPPORTED
    }

    public sealed interface Result permits Resolved, DynamicFallback, Failed {
    }

    public record Resolved(@NotNull ScopeResolvedMethod method) implements Result {
        public Resolved {
            Objects.requireNonNull(method, "method");
        }
    }

    public record DynamicFallback(@NotNull DynamicKind dynamicKind,
                                  @NotNull DynamicFallbackReason reason) implements Result {
        public DynamicFallback {
            Objects.requireNonNull(dynamicKind, "dynamicKind");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record Failed(@NotNull FailureKind kind,
                         @NotNull String message) implements Result {
        public Failed {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(message, "message");
        }
    }

    private record MethodCandidate(@NotNull ScopeResolvedMethod resolved) {
        private MethodCandidate {
            Objects.requireNonNull(resolved, "resolved");
        }
    }

    private sealed interface CandidateSelection permits CandidateSelected, CandidateAmbiguous, CandidateRejected {
    }

    private record CandidateSelected(@NotNull MethodCandidate candidate) implements CandidateSelection {
        private CandidateSelected {
            Objects.requireNonNull(candidate, "candidate");
        }
    }

    private record CandidateAmbiguous(@NotNull List<MethodCandidate> pool) implements CandidateSelection {
        private CandidateAmbiguous {
            pool = List.copyOf(pool);
        }
    }

    private record CandidateRejected(@NotNull String message) implements CandidateSelection {
        private CandidateRejected {
            Objects.requireNonNull(message, "message");
        }
    }

    /// Resolve an instance-style method call.
    public static @NotNull Result resolveInstanceMethod(@NotNull ClassRegistry registry,
                                                        @NotNull GdType receiverType,
                                                        @NotNull String methodName,
                                                        @NotNull List<GdType> argTypes) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(receiverType, "receiverType");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(argTypes, "argTypes");

        try {
            if (methodName.equals("_init")) {
                throw new ScopeMethodResolutionException(
                        FailureKind.CONSTRUCTOR_ROUTE_UNSUPPORTED,
                        "Constructor member '_init' must not be resolved through ordinary instance method lookup"
                );
            }
            if (receiverType instanceof GdObjectType objectType) {
                return resolveKnownObjectInstanceMethod(registry, objectType, methodName, argTypes);
            }
            if (receiverType instanceof GdVariantType) {
                return new DynamicFallback(DynamicKind.VARIANT_DYNAMIC, DynamicFallbackReason.VARIANT_RECEIVER);
            }
            return resolveBuiltinInstanceMethod(registry, receiverType, methodName, argTypes);
        } catch (ScopeMethodResolutionException ex) {
            return new Failed(ex.kind(), ex.getMessage());
        }
    }

    /// Resolve a static/type-meta method call.
    ///
    /// Constructor routes are explicitly excluded here and must be lowered through dedicated
    /// constructor resolution instead of pretending to be a normal method call.
    public static @NotNull Result resolveStaticMethod(@NotNull ClassRegistry registry,
                                                      @NotNull ScopeTypeMeta receiverTypeMeta,
                                                      @NotNull String methodName,
                                                      @NotNull List<GdType> argTypes) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(receiverTypeMeta, "receiverTypeMeta");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(argTypes, "argTypes");

        try {
            if (methodName.equals("new")) {
                throw new ScopeMethodResolutionException(
                        FailureKind.CONSTRUCTOR_ROUTE_UNSUPPORTED,
                        "Constructor lookup for type '" + receiverTypeMeta.sourceName() +
                                "' must use constructor resolution instead of static method lookup"
                );
            }
            var ownerClass = resolveStaticOwnerClass(registry, receiverTypeMeta);
            var candidates = collectMethodCandidates(registry, ownerClass, methodName).stream()
                    .filter(candidate -> candidate.resolved().isStatic())
                    .toList();
            if (candidates.isEmpty()) {
                throw new ScopeMethodResolutionException(
                        FailureKind.METHOD_NOT_FOUND,
                        "Static method '" + methodName + "' not found on type '" + receiverTypeMeta.sourceName() + "'"
                );
            }
            var selection = chooseBestCandidate(
                    registry,
                    methodName,
                    receiverTypeMeta.sourceName(),
                    candidates,
                    argTypes,
                    false
            );
            return switch (selection) {
                case CandidateSelected selected -> new Resolved(selected.candidate().resolved());
                case CandidateAmbiguous ambiguous -> new Failed(
                        FailureKind.AMBIGUOUS_OVERLOAD,
                        "Ambiguous overload for method '" + methodName + "' on type '" +
                                receiverTypeMeta.sourceName() + "': " + renderCandidates(ambiguous.pool())
                );
                case CandidateRejected rejected -> new Failed(FailureKind.NO_APPLICABLE_OVERLOAD, rejected.message());
            };
        } catch (ScopeMethodResolutionException ex) {
            return new Failed(ex.kind(), ex.getMessage());
        }
    }

    private static @NotNull Result resolveKnownObjectInstanceMethod(@NotNull ClassRegistry registry,
                                                                    @NotNull GdObjectType receiverType,
                                                                    @NotNull String methodName,
                                                                    @NotNull List<GdType> argTypes) {
        var classDef = registry.getClassDef(receiverType);
        if (classDef == null) {
            return new DynamicFallback(DynamicKind.OBJECT_DYNAMIC, DynamicFallbackReason.RECEIVER_METADATA_UNKNOWN);
        }

        var candidates = collectMethodCandidates(registry, classDef, methodName);
        if (candidates.isEmpty()) {
            return new DynamicFallback(DynamicKind.OBJECT_DYNAMIC, DynamicFallbackReason.METHOD_MISSING);
        }

        var selection = chooseBestCandidate(registry, methodName, receiverType.getTypeName(), candidates, argTypes, true);
        return switch (selection) {
            case CandidateSelected selected -> new Resolved(selected.candidate().resolved());
            case CandidateAmbiguous _ ->
                    new DynamicFallback(DynamicKind.OBJECT_DYNAMIC, DynamicFallbackReason.AMBIGUOUS_OVERLOAD);
            case CandidateRejected rejected -> new Failed(FailureKind.NO_APPLICABLE_OVERLOAD, rejected.message());
        };
    }

    private static @NotNull Result resolveBuiltinInstanceMethod(@NotNull ClassRegistry registry,
                                                                @NotNull GdType receiverType,
                                                                @NotNull String methodName,
                                                                @NotNull List<GdType> argTypes) {
        var lookupName = normalizeBuiltinReceiverLookupName(receiverType);
        var builtinClass = registry.findBuiltinClass(lookupName);
        if (builtinClass == null) {
            return new Failed(
                    FailureKind.BUILTIN_CLASS_NOT_FOUND,
                    "Builtin class '" + receiverType.getTypeName() +
                            "' not found for call_method receiver (lookup key: '" + lookupName + "')"
            );
        }
        var candidates = collectMethodCandidates(registry, builtinClass, methodName);
        if (candidates.isEmpty()) {
            return new Failed(
                    FailureKind.METHOD_NOT_FOUND,
                    "Method '" + methodName + "' not found in builtin class '" + receiverType.getTypeName() + "'"
            );
        }
        var selection = chooseBestCandidate(registry, methodName, receiverType.getTypeName(), candidates, argTypes, false);
        return switch (selection) {
            case CandidateSelected selected -> new Resolved(selected.candidate().resolved());
            case CandidateAmbiguous ambiguous -> new Failed(
                    FailureKind.AMBIGUOUS_OVERLOAD,
                    "Ambiguous overload for method '" + methodName + "' on type '" +
                            receiverType.getTypeName() + "': " + renderCandidates(ambiguous.pool())
            );
            case CandidateRejected rejected -> new Failed(FailureKind.NO_APPLICABLE_OVERLOAD, rejected.message());
        };
    }

    private static @NotNull List<MethodCandidate> collectMethodCandidates(@NotNull ClassRegistry registry,
                                                                          @NotNull ClassDef startClass,
                                                                          @NotNull String methodName) {
        var out = new ArrayList<MethodCandidate>();
        ClassDef current = startClass;
        var visited = new HashSet<String>();
        var ownerDistance = 0;
        while (current != null) {
            if (!visited.add(current.getName())) {
                break;
            }
            var ownerKind = resolveOwnerKind(registry, current.getName());
            for (var function : current.getFunctions()) {
                if (!function.getName().equals(methodName)) {
                    continue;
                }
                out.add(new MethodCandidate(toResolvedMethod(registry, ownerKind, current, function, ownerDistance)));
            }
            var superCanonicalName = current.getSuperName();
            if (superCanonicalName.isBlank()) {
                break;
            }
            current = registry.getClassDef(new GdObjectType(superCanonicalName));
            ownerDistance++;
        }
        return out;
    }

    /// Candidate ranking is intentionally shared with the backend baseline:
    /// - only applicable signatures survive first
    /// - nearest owner distance wins
    /// - instance methods win over static ones for instance calls
    /// - non-vararg wins over vararg
    /// - if several equally-best object candidates remain, the caller may choose dynamic fallback
    private static @NotNull CandidateSelection chooseBestCandidate(@NotNull ClassRegistry registry,
                                                                   @NotNull String methodName,
                                                                   @NotNull String receiverDisplayName,
                                                                   @NotNull List<MethodCandidate> candidates,
                                                                   @NotNull List<GdType> argTypes,
                                                                   boolean preferInstanceOverStatic) {
        var applicable = new ArrayList<MethodCandidate>();
        for (var candidate : candidates) {
            if (matchesArguments(registry, candidate.resolved(), argTypes)) {
                applicable.add(candidate);
            }
        }
        if (applicable.isEmpty()) {
            var preferred = choosePreferredCandidate(candidates, preferInstanceOverStatic);
            var mismatchReason = buildMismatchReason(registry, preferred.resolved(), argTypes);
            return new CandidateRejected(
                    "No applicable overload for method '" + methodName + "' on type '" +
                            receiverDisplayName + "': " + mismatchReason + ". candidates: " +
                            renderCandidates(candidates)
            );
        }

        var minOwnerDistance = applicable.stream().mapToInt(candidate -> candidate.resolved().ownerDistance()).min().orElse(0);
        var ownerPool = applicable.stream()
                .filter(candidate -> candidate.resolved().ownerDistance() == minOwnerDistance)
                .toList();

        var pool = ownerPool;
        if (preferInstanceOverStatic) {
            var nonStaticPool = ownerPool.stream().filter(candidate -> !candidate.resolved().isStatic()).toList();
            pool = nonStaticPool.isEmpty() ? ownerPool : nonStaticPool;
        }

        var nonVarargPool = pool.stream().filter(candidate -> !candidate.resolved().isVararg()).toList();
        pool = nonVarargPool.isEmpty() ? pool : nonVarargPool;

        if (pool.size() == 1) {
            return new CandidateSelected(pool.getFirst());
        }
        return new CandidateAmbiguous(pool);
    }

    private static @NotNull MethodCandidate choosePreferredCandidate(@NotNull List<MethodCandidate> candidates,
                                                                     boolean preferInstanceOverStatic) {
        var minOwnerDistance = candidates.stream().mapToInt(candidate -> candidate.resolved().ownerDistance()).min().orElse(0);
        var ownerPool = candidates.stream()
                .filter(candidate -> candidate.resolved().ownerDistance() == minOwnerDistance)
                .toList();

        var pool = ownerPool;
        if (preferInstanceOverStatic) {
            var nonStaticPool = ownerPool.stream().filter(candidate -> !candidate.resolved().isStatic()).toList();
            pool = nonStaticPool.isEmpty() ? ownerPool : nonStaticPool;
        }

        var nonVarargPool = pool.stream().filter(candidate -> !candidate.resolved().isVararg()).toList();
        pool = nonVarargPool.isEmpty() ? pool : nonVarargPool;
        return pool.getFirst();
    }

    private static @NotNull String buildMismatchReason(@NotNull ClassRegistry registry,
                                                       @NotNull ScopeResolvedMethod candidate,
                                                       @NotNull List<GdType> argTypes) {
        var fixedCount = candidate.parameters().size();
        var providedCount = argTypes.size();
        if (providedCount < fixedCount && !canOmitTrailingParameters(candidate.parameters(), providedCount)) {
            var missingParam = firstMissingRequiredParameter(candidate.parameters(), providedCount);
            return "Too few arguments for method '" + candidate.ownerClass().getName() + "." + candidate.methodName() +
                    "': missing required parameter #" + (missingParam + 1) + " ('" +
                    candidate.parameters().get(missingParam).name() + "')";
        }
        if (!candidate.isVararg() && providedCount > fixedCount) {
            return "Too many arguments for method '" + candidate.ownerClass().getName() + "." + candidate.methodName() +
                    "': expected " + fixedCount + ", got " + providedCount;
        }
        var providedFixedCount = Math.min(providedCount, fixedCount);
        for (var i = 0; i < providedFixedCount; i++) {
            var argType = argTypes.get(i);
            var param = candidate.parameters().get(i);
            if (!registry.checkAssignable(argType, param.type())) {
                return "Cannot assign value of type '" + argType.getTypeName() +
                        "' to method parameter #" + (i + 1) + " ('" + param.name() +
                        "') of type '" + param.type().getTypeName() + "'";
            }
        }
        return "no compatible signature found";
    }

    private static @NotNull String renderCandidates(@NotNull List<MethodCandidate> candidates) {
        var joiner = new StringJoiner("; ");
        for (var candidate : candidates) {
            var resolved = candidate.resolved();
            joiner.add(renderSignature(resolved) + " [distance=" + resolved.ownerDistance() + "]");
        }
        return joiner.toString();
    }

    private static @NotNull String renderSignature(@NotNull ScopeResolvedMethod resolved) {
        var argsJoiner = new StringJoiner(", ");
        for (var parameter : resolved.parameters()) {
            argsJoiner.add(parameter.type().getTypeName());
        }
        if (resolved.isVararg()) {
            argsJoiner.add("...");
        }
        var staticPrefix = resolved.isStatic() ? "static " : "";
        return staticPrefix + resolved.ownerClass().getName() + "." + resolved.methodName() + "(" + argsJoiner + ")";
    }

    private static boolean matchesArguments(@NotNull ClassRegistry registry,
                                            @NotNull ScopeResolvedMethod candidate,
                                            @NotNull List<GdType> argTypes) {
        var fixedCount = candidate.parameters().size();
        var provided = argTypes.size();
        if (provided < fixedCount && !canOmitTrailingParameters(candidate.parameters(), provided)) {
            return false;
        }
        if (!candidate.isVararg() && provided > fixedCount) {
            return false;
        }
        var providedFixedCount = Math.min(provided, fixedCount);
        for (var i = 0; i < providedFixedCount; i++) {
            var argType = argTypes.get(i);
            var paramType = candidate.parameters().get(i).type();
            if (!registry.checkAssignable(argType, paramType)) {
                return false;
            }
        }
        if (!candidate.isVararg()) {
            return true;
        }
        // Godot varargs are carried through Variant packing, so typed callers should not be forced
        // to prove a strict `T -> Variant` conversion for the tail.
        return true;
    }

    private static boolean canOmitTrailingParameters(@NotNull List<ScopeMethodParameter> parameters,
                                                     int providedCount) {
        for (var i = providedCount; i < parameters.size(); i++) {
            if (!parameters.get(i).hasDefaultValue()) {
                return false;
            }
        }
        return true;
    }

    private static int firstMissingRequiredParameter(@NotNull List<ScopeMethodParameter> parameters,
                                                     int providedCount) {
        for (var i = providedCount; i < parameters.size(); i++) {
            if (!parameters.get(i).hasDefaultValue()) {
                return i;
            }
        }
        return providedCount;
    }

    private static @NotNull ScopeOwnerKind resolveOwnerKind(@NotNull ClassRegistry registry,
                                                            @NotNull String ownerClassName) {
        if (registry.isGdccClass(ownerClassName)) {
            return ScopeOwnerKind.GDCC;
        }
        if (registry.isGdClass(ownerClassName)) {
            return ScopeOwnerKind.ENGINE;
        }
        if (registry.isBuiltinClass(ownerClassName)) {
            return ScopeOwnerKind.BUILTIN;
        }
        throw new ScopeMethodResolutionException(
                FailureKind.UNSUPPORTED_OWNER,
                "Unsupported method owner '" + ownerClassName + "': unable to classify as GDCC/ENGINE/BUILTIN"
        );
    }

    private static @NotNull String normalizeBuiltinReceiverLookupName(@NotNull GdType receiverType) {
        return switch (receiverType) {
            case GdArrayType _ -> "Array";
            case GdDictionaryType _ -> "Dictionary";
            default -> receiverType.getTypeName();
        };
    }

    private static @NotNull ScopeResolvedMethod toResolvedMethod(@NotNull ClassRegistry registry,
                                                                 @NotNull ScopeOwnerKind ownerKind,
                                                                 @NotNull ClassDef ownerClass,
                                                                 @NotNull FunctionDef function,
                                                                 int ownerDistance) {
        var params = new ArrayList<ScopeMethodParameter>();
        var ownerType = resolveOwnerType(ownerKind, ownerClass.getName());
        var startParamIndex = ownerKind == ScopeOwnerKind.GDCC && !function.isStatic()
                ? resolveGdccInstanceParameterStartIndex(ownerClass, function, ownerType)
                : 0;
        for (var i = startParamIndex; i < function.getParameterCount(); i++) {
            var parameter = function.getParameter(i);
            if (parameter == null) {
                throw new ScopeMethodResolutionException(
                        FailureKind.MALFORMED_METADATA,
                        "Method parameter #" + (i + 1) + " metadata is missing for '" +
                                ownerClass.getName() + "." + function.getName() + "'"
                );
            }
            var parameterType = resolveMethodParameterType(registry, ownerClass, function, parameter, i + 1);
            var defaultKind = resolveDefaultArgKind(parameter);
            var defaultLiteral = defaultKind == ScopeDefaultArgKind.LITERAL && parameter instanceof ExtensionFunctionArgument extensionArgument
                    ? extensionArgument.defaultValue()
                    : null;
            var defaultFunctionName = defaultKind == ScopeDefaultArgKind.FUNCTION
                    ? parameter.getDefaultValueFunc()
                    : null;
            params.add(new ScopeMethodParameter(
                    parameter.getName(),
                    parameterType,
                    defaultKind,
                    defaultLiteral,
                    defaultFunctionName
            ));
        }
        var returnType = resolveMethodReturnType(registry, ownerClass, function);
        return new ScopeResolvedMethod(
                ownerKind,
                ownerClass,
                function,
                ownerType,
                returnType,
                params,
                ownerDistance
        );
    }

    /// GDCC currently has two stable metadata layouts for instance methods:
    /// - lowered/shared LIR may materialize a synthetic leading `self` parameter
    /// - frontend class skeleton publishes only user-visible parameters
    /// Shared method lookup accepts both so frontend semantic phases can reuse the resolver without
    /// forcing skeleton metadata to mimic backend lowering shape.
    private static int resolveGdccInstanceParameterStartIndex(@NotNull ClassDef ownerClass,
                                                              @NotNull FunctionDef function,
                                                              @NotNull GdType ownerType) {
        var firstParam = function.getParameter(0);
        if (firstParam == null) {
            return 0;
        }
        if (!firstParam.getName().equals("self")) {
            return 0;
        }
        if (!firstParam.getType().getTypeName().equals(ownerType.getTypeName())) {
            throw new ScopeMethodResolutionException(
                    FailureKind.MALFORMED_METADATA,
                    "GDCC method '" + ownerClass.getName() + "." + function.getName()
                            + "' has synthetic self parameter of type '" + firstParam.getType().getTypeName()
                            + "', expected '" + ownerType.getTypeName() + "'"
            );
        }
        return 1;
    }

    private static @NotNull ScopeDefaultArgKind resolveDefaultArgKind(@NotNull ParameterDef parameter) {
        if (parameter instanceof ExtensionFunctionArgument extensionArgument &&
                extensionArgument.defaultValue() != null && !extensionArgument.defaultValue().isBlank()) {
            return ScopeDefaultArgKind.LITERAL;
        }
        var defaultValueFunc = parameter.getDefaultValueFunc();
        if (defaultValueFunc != null && !defaultValueFunc.isBlank()) {
            return ScopeDefaultArgKind.FUNCTION;
        }
        return ScopeDefaultArgKind.NONE;
    }

    private static @NotNull GdType resolveMethodParameterType(@NotNull ClassRegistry registry,
                                                              @NotNull ClassDef ownerClass,
                                                              @NotNull FunctionDef function,
                                                              @NotNull ParameterDef parameter,
                                                              int parameterIndexBaseOne) {
        if (parameter instanceof ExtensionFunctionArgument extensionArgument) {
            return parseExtensionTypeWithScopeHelper(
                    registry,
                    extensionArgument.type(),
                    "method parameter #" + parameterIndexBaseOne + " of '" +
                            ownerClass.getName() + "." + function.getName() + "'"
            );
        }
        return parameter.getType();
    }

    private static @NotNull GdType resolveMethodReturnType(@NotNull ClassRegistry registry,
                                                           @NotNull ClassDef ownerClass,
                                                           @NotNull FunctionDef function) {
        if (function instanceof ExtensionBuiltinClass.ClassMethod builtinMethod) {
            var rawReturnType = builtinMethod.returnValue() != null
                    ? builtinMethod.returnValue().type()
                    : builtinMethod.returnType();
            return parseExtensionTypeWithScopeHelper(
                    registry,
                    rawReturnType,
                    "return type of '" + ownerClass.getName() + "." + function.getName() + "'"
            );
        }
        if (function instanceof ExtensionGdClass.ClassMethod gdMethod) {
            var returnInfo = gdMethod.returnValue();
            var rawReturnType = returnInfo == null ? "void" : returnInfo.type();
            return parseExtensionTypeWithScopeHelper(
                    registry,
                    rawReturnType,
                    "return type of '" + ownerClass.getName() + "." + function.getName() + "'"
            );
        }
        return function.getReturnType();
    }

    private static @NotNull GdType parseExtensionTypeWithScopeHelper(@NotNull ClassRegistry registry,
                                                                     @Nullable String rawTypeName,
                                                                     @NotNull String typeUseSite) {
        try {
            return ScopeTypeParsers.parseExtensionTypeMetadata(rawTypeName, typeUseSite, registry);
        } catch (IllegalArgumentException ex) {
            throw new ScopeMethodResolutionException(FailureKind.MALFORMED_METADATA, ex.getMessage());
        }
    }

    private static @NotNull GdType resolveOwnerType(@NotNull ScopeOwnerKind ownerKind,
                                                    @NotNull String ownerClassName) {
        return switch (ownerKind) {
            case GDCC, ENGINE -> new GdObjectType(ownerClassName);
            case BUILTIN -> {
                var parsed = ClassRegistry.tryParseStrictTextType(ownerClassName, null);
                if (parsed == null) {
                    throw new ScopeMethodResolutionException(
                            FailureKind.MALFORMED_METADATA,
                            "Unsupported builtin owner type '" + ownerClassName + "'"
                    );
                }
                yield parsed;
            }
        };
    }

    private static @NotNull ClassDef resolveStaticOwnerClass(@NotNull ClassRegistry registry,
                                                             @NotNull ScopeTypeMeta receiverTypeMeta) {
        if (receiverTypeMeta.pseudoType() || receiverTypeMeta.kind() == ScopeTypeMetaKind.GLOBAL_ENUM) {
            throw new ScopeMethodResolutionException(
                    FailureKind.UNSUPPORTED_STATIC_RECEIVER,
                    "Type meta '" + receiverTypeMeta.sourceName() + "' does not support static method lookup"
            );
        }
        if (receiverTypeMeta.declaration() instanceof ClassDef classDef) {
            return classDef;
        }
        return switch (receiverTypeMeta.kind()) {
            case BUILTIN -> {
                var lookupName = normalizeBuiltinReceiverLookupName(receiverTypeMeta.instanceType());
                var builtinClass = registry.findBuiltinClass(lookupName);
                if (builtinClass == null) {
                    throw new ScopeMethodResolutionException(
                            FailureKind.BUILTIN_CLASS_NOT_FOUND,
                            "Builtin class '" + receiverTypeMeta.sourceName() +
                                    "' not found for static method receiver (lookup key: '" + lookupName + "')"
                    );
                }
                yield builtinClass;
            }
            case ENGINE_CLASS, GDCC_CLASS -> {
                if (receiverTypeMeta.instanceType() instanceof GdObjectType objectType) {
                    var classDef = registry.getClassDef(objectType);
                    if (classDef != null) {
                        yield classDef;
                    }
                }
                throw new ScopeMethodResolutionException(
                        FailureKind.UNSUPPORTED_STATIC_RECEIVER,
                        "Class metadata for static receiver '" + receiverTypeMeta.sourceName() + "' is unavailable"
                );
            }
            case GLOBAL_ENUM -> throw new ScopeMethodResolutionException(
                    FailureKind.UNSUPPORTED_STATIC_RECEIVER,
                    "Type meta '" + receiverTypeMeta.sourceName() + "' does not support static method lookup"
            );
        };
    }
}

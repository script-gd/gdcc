package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.scope.ClassDef;
import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.type.GdNilType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Shared resolver for `call_method` dispatch mode and method signature lookup.
///
/// Phase 1 scope:
/// - Resolve static/direct call metadata for GDCC / ENGINE / BUILTIN receivers.
/// - Resolve known-type missing-method as compile-time error.
/// - Return OBJECT_DYNAMIC / VARIANT_DYNAMIC modes for not-yet-implemented dynamic paths.
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

    public record MethodParamSpec(@NotNull String name, @NotNull GdType type) {
        public MethodParamSpec {
            Objects.requireNonNull(name);
            Objects.requireNonNull(type);
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

        if (receiverType instanceof GdObjectType objectType) {
            if (objectType.checkGdccType(bodyBuilder.classRegistry())) {
                return resolveKnownObjectMethod(bodyBuilder, DispatchMode.GDCC, objectType, methodName, argVars);
            }
            if (objectType.checkEngineType(bodyBuilder.classRegistry())) {
                return resolveKnownObjectMethod(bodyBuilder, DispatchMode.ENGINE, objectType, methodName, argVars);
            }
            return dynamicPlaceholder(DispatchMode.OBJECT_DYNAMIC, receiverType, methodName);
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

    private static @NotNull ResolvedMethodCall resolveKnownObjectMethod(@NotNull CBodyBuilder bodyBuilder,
                                                                        @NotNull DispatchMode mode,
                                                                        @NotNull GdObjectType receiverType,
                                                                        @NotNull String methodName,
                                                                        @NotNull List<LirVariable> argVars) {
        var classDef = bodyBuilder.classRegistry().getClassDef(receiverType);
        if (classDef == null) {
            return dynamicPlaceholder(DispatchMode.OBJECT_DYNAMIC, receiverType, methodName);
        }

        var candidates = collectMethodCandidates(bodyBuilder, mode, classDef, methodName);
        if (candidates.isEmpty()) {
            throw bodyBuilder.invalidInsn("Method '" + methodName + "' not found in type '" +
                    receiverType.getTypeName() + "' or its super classes");
        }
        return chooseBestCandidate(bodyBuilder, methodName, receiverType, candidates, argVars);
    }

    private static @NotNull ResolvedMethodCall resolveBuiltinMethod(@NotNull CBodyBuilder bodyBuilder,
                                                                    @NotNull GdType receiverType,
                                                                    @NotNull String methodName,
                                                                    @NotNull List<LirVariable> argVars) {
        var builtinClass = bodyBuilder.classRegistry().findBuiltinClass(receiverType.getTypeName());
        if (builtinClass == null) {
            throw bodyBuilder.invalidInsn("Builtin class '" + receiverType.getTypeName() +
                    "' not found for call_method receiver");
        }
        var candidates = collectMethodCandidates(bodyBuilder, DispatchMode.BUILTIN, builtinClass, methodName);
        if (candidates.isEmpty()) {
            throw bodyBuilder.invalidInsn("Method '" + methodName + "' not found in builtin class '" +
                    receiverType.getTypeName() + "'");
        }
        return chooseBestCandidate(bodyBuilder, methodName, receiverType, candidates, argVars);
    }

    private static @NotNull List<ResolvedMethodCall> collectMethodCandidates(@NotNull CBodyBuilder bodyBuilder,
                                                                              @NotNull DispatchMode mode,
                                                                              @NotNull ClassDef startClass,
                                                                              @NotNull String methodName) {
        var registry = bodyBuilder.classRegistry();
        var out = new ArrayList<ResolvedMethodCall>();
        ClassDef current = startClass;
        var visited = new ArrayList<String>();
        while (current != null) {
            if (visited.contains(current.getName())) {
                break;
            }
            visited.add(current.getName());
            for (var function : current.getFunctions()) {
                if (!function.getName().equals(methodName)) {
                    continue;
                }
                out.add(toResolvedMethodCall(bodyBuilder, mode, current, function));
            }
            var superName = current.getSuperName();
            if (superName == null || superName.isBlank()) {
                break;
            }
            current = registry.getClassDef(new GdObjectType(superName));
        }
        return out;
    }

    private static @NotNull ResolvedMethodCall chooseBestCandidate(@NotNull CBodyBuilder bodyBuilder,
                                                                   @NotNull String methodName,
                                                                   @NotNull GdType receiverType,
                                                                   @NotNull List<ResolvedMethodCall> candidates,
                                                                   @NotNull List<LirVariable> argVars) {
        var applicable = new ArrayList<ResolvedMethodCall>();
        for (var candidate : candidates) {
            if (matchesArguments(bodyBuilder, candidate, argVars)) {
                applicable.add(candidate);
            }
        }
        if (applicable.isEmpty()) {
            for (var candidate : candidates) {
                if (!candidate.isStatic()) {
                    return candidate;
                }
            }
            return candidates.getFirst();
        }

        var nonStaticApplicable = applicable.stream().filter(c -> !c.isStatic()).toList();
        var pool = nonStaticApplicable.isEmpty() ? applicable : nonStaticApplicable;

        var nonVarargPool = pool.stream().filter(c -> !c.isVararg()).toList();
        if (nonVarargPool.size() == 1) {
            return nonVarargPool.getFirst();
        }
        if (nonVarargPool.size() > 1) {
            throw bodyBuilder.invalidInsn("Ambiguous overload for method '" + methodName + "' on type '" +
                    receiverType.getTypeName() + "'");
        }

        if (pool.size() > 1) {
            throw bodyBuilder.invalidInsn("Ambiguous vararg overload for method '" + methodName + "' on type '" +
                    receiverType.getTypeName() + "'");
        }
        return pool.getFirst();
    }

    private static boolean matchesArguments(@NotNull CBodyBuilder bodyBuilder,
                                            @NotNull ResolvedMethodCall candidate,
                                            @NotNull List<LirVariable> argVars) {
        var fixedCount = candidate.parameters().size();
        var provided = argVars.size();
        if (provided < fixedCount) {
            return false;
        }
        if (!candidate.isVararg() && provided != fixedCount) {
            return false;
        }
        for (var i = 0; i < fixedCount; i++) {
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
            params.add(new MethodParamSpec(parameter.getName(), parameter.getType()));
        }

        var ownerType = resolveOwnerType(bodyBuilder, mode, ownerClass.getName());
        var cFunctionName = renderMethodCFunctionName(mode, ownerClass.getName(), function.getName());
        return new ResolvedMethodCall(
                mode,
                function.getName(),
                ownerClass.getName(),
                ownerType,
                cFunctionName,
                function.getReturnType(),
                params,
                function.isVararg(),
                function.isStatic()
        );
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

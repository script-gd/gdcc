package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.frontend.constant.GdConstantValue;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ScopeOwnerKind;
import gd.script.gdcc.scope.resolver.ScopeMethodParameter;
import gd.script.gdcc.scope.resolver.ScopeMethodResolver;
import gd.script.gdcc.scope.resolver.ScopeResolvedMethod;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Shared pure signature selection for constant member calls (const plan §5.5).
///
/// Selection runs the same `ScopeMethodResolver` overload machinery a regular builtin
/// instance-method call would use, with the ordinary frontend boundary ranks as the
/// argument-compatibility rule, then re-checks the winner against the constant-call admission
/// surface: builtin owner, instance method, fixed arity with no vararg and no omitted defaults,
/// and parameter/return types inside the constant value set. Nothing here reads AST, Scope, or
/// analysis side tables, so skeleton-time `LookupContext` implementations can delegate directly.
final class FrontendConstCallSelectionSupport {
    private FrontendConstCallSelectionSupport() {
    }

    /// Selects the exact signature a constant member call would bind to.
    ///
    /// `Rejected` results are terminal for the call and describe the first admission failure:
    /// non-constant receiver/argument types never reach metadata selection, and a resolved
    /// signature outside the v1 dispatch surface (static, vararg, default-filling,
    /// non-constant types) is rejected rather than approximated.
    static @NotNull LookupContext.SelectionResult select(
            @NotNull ClassRegistry classRegistry,
            @NotNull GdType receiverType,
            @NotNull String methodName,
            @NotNull List<GdType> argumentTypes
    ) {
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        Objects.requireNonNull(receiverType, "receiverType must not be null");
        Objects.requireNonNull(methodName, "methodName must not be null");
        var argTypes = List.copyOf(Objects.requireNonNull(argumentTypes, "argumentTypes must not be null"));
        if (!GdConstantValue.isSupportedConstantType(receiverType)) {
            return rejected(
                    "receiver type '" + receiverType.getTypeName()
                            + "' cannot own a constant member call"
            );
        }
        for (var index = 0; index < argTypes.size(); index++) {
            var argumentType = argTypes.get(index);
            if (!GdConstantValue.isSupportedConstantType(argumentType)) {
                return rejected(
                        "argument #" + (index + 1) + " of type '" + argumentType.getTypeName()
                                + "' is not a supported constant type"
                );
            }
        }
        var result = ScopeMethodResolver.resolveInstanceMethodWithParameterRank(
                classRegistry,
                receiverType,
                methodName,
                argTypes,
                (_, sourceType, targetType) -> FrontendVariantBoundaryCompatibility
                        .frontendBoundarySpecificityRank(classRegistry, sourceType, targetType)
        );
        return switch (result) {
            case ScopeMethodResolver.Resolved resolved -> toSelection(resolved.method(), argTypes.size());
            case ScopeMethodResolver.DynamicFallback dynamic -> rejected(
                    "member call '" + methodName + "' resolves through runtime-dynamic dispatch ("
                            + dynamic.reason() + ")"
            );
            case ScopeMethodResolver.Failed failed -> rejected(failed.message());
        };
    }

    /// Maps a resolved metadata method to a frozen selection, rejecting every dispatch shape the
    /// v1 surface does not admit instead of approximating it.
    private static @NotNull LookupContext.SelectionResult toSelection(
            @NotNull ScopeResolvedMethod method,
            int providedArgumentCount
    ) {
        if (method.ownerKind() != ScopeOwnerKind.BUILTIN) {
            return rejected(
                    "selected method '" + method.methodName()
                            + "' is not owned by a controlled builtin class"
            );
        }
        if (method.isStatic()) {
            return rejected("static methods are not constant callables");
        }
        if (method.isVararg()) {
            return rejected("vararg methods are not constant callables");
        }
        if (method.parameters().size() != providedArgumentCount) {
            return rejected("omitted-default parameters are not filled in for constant calls");
        }
        var parameterTypes = method.parameters().stream().map(ScopeMethodParameter::type).toList();
        for (var parameterType : parameterTypes) {
            if (!GdConstantValue.isSupportedConstantType(parameterType)) {
                return rejected(
                        "selected method '" + method.methodName() + "' declares parameter type '"
                                + parameterType.getTypeName() + "' outside the constant value surface"
                );
            }
        }
        if (!GdConstantValue.isSupportedConstantType(method.returnType())) {
            return rejected(
                    "selected method '" + method.methodName() + "' returns '"
                            + method.returnType().getTypeName()
                            + "', which is not a supported constant type"
            );
        }
        var key = new ConstCallableKey(
                method.ownerType(),
                method.methodName(),
                ConstCallableKey.InvocationKind.INSTANCE_METHOD,
                parameterTypes
        );
        return new LookupContext.SelectionResult.Selected(key, method.returnType());
    }

    private static @NotNull LookupContext.SelectionResult.Rejected rejected(@NotNull String reason) {
        return new LookupContext.SelectionResult.Rejected(reason);
    }

    /// Exact-type identity used when a selected signature must match a registered key or a frozen
    /// value must match a declared boundary: identical instances, or same runtime class and name.
    /// This mirrors the analysis-side `sameType` contract without depending on analysis data.
    static boolean sameExactType(@NotNull GdType first, @NotNull GdType second) {
        Objects.requireNonNull(first, "first must not be null");
        Objects.requireNonNull(second, "second must not be null");
        return first == second
                || first.getClass() == second.getClass()
                && first.getTypeName().equals(second.getTypeName());
    }
}

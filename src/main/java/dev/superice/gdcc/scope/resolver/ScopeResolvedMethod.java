package dev.superice.gdcc.scope.resolver;

import dev.superice.gdcc.scope.ClassDef;
import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.scope.ScopeOwnerKind;
import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Shared method lookup result consumed by frontend and backend.
///
/// The record keeps only metadata needed to make semantic decisions consistently across layers:
/// - resolved owner domain/class/function
/// - normalized owner/return/parameter types
/// - owner distance used by overload ranking
///
/// It intentionally does **not** encode backend-only details such as generated C function names,
/// dynamic dispatch helpers, temporary variables, or constructor lowering strategy.
public record ScopeResolvedMethod(
        @NotNull ScopeOwnerKind ownerKind,
        @NotNull ClassDef ownerClass,
        @NotNull FunctionDef function,
        @NotNull GdType ownerType,
        @NotNull GdType returnType,
        @NotNull List<ScopeMethodParameter> parameters,
        int ownerDistance
) {
    public ScopeResolvedMethod {
        Objects.requireNonNull(ownerKind, "ownerKind");
        Objects.requireNonNull(ownerClass, "ownerClass");
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(ownerType, "ownerType");
        Objects.requireNonNull(returnType, "returnType");
        parameters = List.copyOf(parameters);
        if (ownerDistance < 0) {
            throw new IllegalArgumentException("ownerDistance must be >= 0");
        }
    }

    public @NotNull String methodName() {
        return function.getName();
    }

    public boolean isVararg() {
        return function.isVararg();
    }

    public boolean isStatic() {
        return function.isStatic();
    }
}

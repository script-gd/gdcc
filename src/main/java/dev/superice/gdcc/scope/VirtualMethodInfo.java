package dev.superice.gdcc.scope;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public record VirtualMethodInfo(
        @NotNull String ownerClassName,
        boolean engineMethod,
        @NotNull FunctionDef function
) {
    public VirtualMethodInfo {
        Objects.requireNonNull(ownerClassName, "ownerClassName");
        Objects.requireNonNull(function, "function");
    }

    /// Engine virtual overrides must keep the callable surface exact; backend/frontend both reuse
    /// this predicate so they cannot silently drift into separate name-only rules.
    public boolean checkOverrideSignature(@NotNull FunctionDef overrideFunction) {
        return checkOverrideSignature(overrideFunction, false);
    }

    /// Backend LIR still carries a synthetic leading `self` parameter for instance methods, while
    /// frontend source declarations do not. Both consumers still compare against the same engine
    /// virtual metadata; this flag only normalizes that backend-owned wrapper detail.
    public boolean checkOverrideSignature(@NotNull FunctionDef overrideFunction, boolean ignoreLeadingSelfParameter) {
        Objects.requireNonNull(overrideFunction, "overrideFunction");
        if (overrideFunction.isStatic() || function.isStatic()) {
            return false;
        }
        if (overrideFunction.isVararg() != function.isVararg()) {
            return false;
        }
        var overrideParameterStart = 0;
        if (ignoreLeadingSelfParameter && overrideFunction.getParameterCount() > 0) {
            var leadingParameter = Objects.requireNonNull(
                    overrideFunction.getParameter(0),
                    "override parameter #0 is missing"
            );
            if (leadingParameter.getName().equals("self")) {
                overrideParameterStart = 1;
            }
        }
        if (overrideFunction.getParameterCount() - overrideParameterStart != function.getParameterCount()) {
            return false;
        }
        for (var parameterIndex = 0; parameterIndex < function.getParameterCount(); parameterIndex++) {
            var overrideParameter = Objects.requireNonNull(
                    overrideFunction.getParameter(parameterIndex + overrideParameterStart),
                    "override parameter #" + parameterIndex + " is missing"
            );
            var expectedParameter = Objects.requireNonNull(
                    function.getParameter(parameterIndex),
                    "virtual parameter #" + parameterIndex + " is missing"
            );
            if (!overrideParameter.getType().equals(expectedParameter.getType())) {
                return false;
            }
        }
        return overrideFunction.getReturnType().equals(function.getReturnType());
    }
}

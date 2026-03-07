package dev.superice.gdcc.scope.resolver;

import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Shared method-parameter view used by `ScopeMethodResolver`.
///
/// The resolver flattens backend/frontend-relevant parts of method metadata into a stable shape:
/// - resolved parameter type after extension metadata normalization
/// - whether the parameter has a default value
/// - which default source is referenced, if any
public record ScopeMethodParameter(
        @NotNull String name,
        @NotNull GdType type,
        @NotNull ScopeDefaultArgKind defaultArgKind,
        @Nullable String defaultLiteral,
        @Nullable String defaultFunctionName
) {
    public ScopeMethodParameter {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(defaultArgKind, "defaultArgKind");
        if (defaultArgKind == ScopeDefaultArgKind.LITERAL && (defaultLiteral == null || defaultLiteral.isBlank())) {
            throw new IllegalArgumentException("defaultLiteral must be present when defaultArgKind is LITERAL");
        }
        if (defaultArgKind == ScopeDefaultArgKind.FUNCTION &&
                (defaultFunctionName == null || defaultFunctionName.isBlank())) {
            throw new IllegalArgumentException("defaultFunctionName must be present when defaultArgKind is FUNCTION");
        }
    }

    public boolean hasDefaultValue() {
        return defaultArgKind != ScopeDefaultArgKind.NONE;
    }
}

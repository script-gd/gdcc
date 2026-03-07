package dev.superice.gdcc.scope;

import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Scope-local description of a resolved value binding.
///
/// `declaration` is intentionally opaque in v1 so frontend/backend callers can
/// attach the originating metadata object without forcing a shared declaration hierarchy.
public record ScopeValue(
        @NotNull String name,
        @NotNull GdType type,
        @NotNull ScopeValueKind kind,
        @Nullable Object declaration,
        boolean constant,
        boolean staticMember
) {
    public ScopeValue {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(kind, "kind");
    }
}

package dev.superice.gdcc.scope;

import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Strictly resolved type-meta binding.
///
/// `instanceType` is the runtime/value-side type after leaving the meta namespace.
/// For example:
/// - builtin `String` -> `GdStringType.STRING`
/// - engine/gdcc class `Node` -> `GdObjectType("Node")`
/// - enum type -> `GdIntType.INT`
public record ScopeTypeMeta(
        @NotNull String name,
        @NotNull GdType instanceType,
        @NotNull ScopeTypeMetaKind kind,
        @Nullable Object declaration,
        boolean pseudoType
) {
    public ScopeTypeMeta {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(instanceType, "instanceType");
        Objects.requireNonNull(kind, "kind");
    }
}

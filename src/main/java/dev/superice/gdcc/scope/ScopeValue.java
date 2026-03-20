package dev.superice.gdcc.scope;

import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Value-side binding descriptor returned by lexical scope lookup.
///
/// This record intentionally keeps only the minimum stable metadata that both frontend and backend
/// style callers can share without forcing a large common declaration hierarchy.
///
/// Field semantics:
/// - `name`: source-level identifier text that was resolved.
/// - `type`: runtime/value-side type produced after binding.
/// - `kind`: semantic origin of the binding, such as local, parameter, property, or singleton.
/// - `declaration`: original metadata object when the caller needs to recover richer information.
/// - `constant`: whether the binding should be treated as immutable by later analysis stages.
/// - `writable`: whether the binding may appear as a bare identifier assignment target.
/// - `staticMember`: whether the binding belongs to a type/class context instead of an instance.
///
/// `writable` is intentionally narrower than full mutation semantics. Receiver-based member access,
/// container element mutation, and aliasing-sensitive routes still need usage-aware analysis instead
/// of reusing this lexical binding flag.
///
/// `ScopeValue` does not replace the dedicated type/meta namespace. If the caller is resolving a
/// class name or enum type as a type reference, it should use `ScopeTypeMeta` via `resolveTypeMeta(...)`.
public record ScopeValue(
        @NotNull String name,
        @NotNull GdType type,
        @NotNull ScopeValueKind kind,
        @Nullable Object declaration,
        boolean constant,
        boolean writable,
        boolean staticMember
) {
    /// Validates the mandatory parts of the binding descriptor.
    public ScopeValue {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(kind, "kind");
        if (constant && writable) {
            throw new IllegalArgumentException("constant bindings cannot be writable");
        }
        if (kind == ScopeValueKind.SIGNAL && writable) {
            throw new IllegalArgumentException("signal bindings cannot be writable");
        }
    }
}

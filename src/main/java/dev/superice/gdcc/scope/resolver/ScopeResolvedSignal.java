package dev.superice.gdcc.scope.resolver;

import dev.superice.gdcc.scope.ClassDef;
import dev.superice.gdcc.scope.ScopeOwnerKind;
import dev.superice.gdcc.scope.SignalDef;
import dev.superice.gdcc.type.GdSignalType;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Shared signal lookup result consumed by frontend and backend.
///
/// The record answers four questions after receiver-based signal resolution succeeds:
/// - which metadata domain owns the signal (`ownerKind`)
/// - which class metadata node contributes that signal (`ownerClass`)
/// - which concrete signal definition matched the lookup (`signal`)
/// - which immutable value-side signature that definition exposes (`signalType()`)
///
/// The record intentionally models only **signal metadata**. It does not encode `.emit(...)`
/// overload matching, callable conversion, or runtime connection behavior; those stay in later
/// frontend/backend stages.
public record ScopeResolvedSignal(
        @NotNull ScopeOwnerKind ownerKind,
        @NotNull ClassDef ownerClass,
        @NotNull SignalDef signal
) {
    public ScopeResolvedSignal {
        Objects.requireNonNull(ownerKind, "ownerKind");
        Objects.requireNonNull(ownerClass, "ownerClass");
        Objects.requireNonNull(signal, "signal");
    }

    /// Derives the stable value-side signal signature from the matched metadata declaration.
    public @NotNull GdSignalType signalType() {
        return GdSignalType.from(signal);
    }
}

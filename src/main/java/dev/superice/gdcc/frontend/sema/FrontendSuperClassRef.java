package dev.superice.gdcc.frontend.sema;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Frontend-only steady-state superclass identity.
///
/// This is the only stable carrier of source-facing superclass naming after header discovery.
/// `sourceName` stays available to lexical/frontend consumers, while `canonicalName` is the
/// registry/LIR/backend identity written into `ClassDef`.
public record FrontendSuperClassRef(
        @NotNull String sourceName,
        @NotNull String canonicalName
) {
    public FrontendSuperClassRef {
        Objects.requireNonNull(sourceName, "sourceName must not be null");
        Objects.requireNonNull(canonicalName, "canonicalName must not be null");
    }
}

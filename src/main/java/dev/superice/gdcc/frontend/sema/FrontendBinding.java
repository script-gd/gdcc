package dev.superice.gdcc.frontend.sema;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// One resolved frontend binding fact attached to an AST use site.
///
/// The record stores symbol category plus declaration provenance. It intentionally does not encode
/// usage semantics such as read/write/call, so assignment left-hand sites and ordinary reads share
/// the same container shape.
public record FrontendBinding(
        @NotNull String symbolName,
        @NotNull FrontendBindingKind kind,
        @Nullable Object declarationSite
) {
    public FrontendBinding {
        Objects.requireNonNull(symbolName, "symbolName must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
    }
}

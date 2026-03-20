package dev.superice.gdcc.exception;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Exception thrown when frontend semantic analysis must fail-fast.
///
/// Kept in the shared exception package so semantic failures follow the same
/// repository-wide exception contract as backend and scope failures.
public final class FrontendSemanticException extends GdccException {
    private final @NotNull DiagnosticSnapshot diagnostics;

    public FrontendSemanticException(@NotNull String message, @NotNull DiagnosticSnapshot diagnostics) {
        super(message);
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics must not be null");
    }

    public @NotNull DiagnosticSnapshot diagnostics() {
        return diagnostics;
    }
}

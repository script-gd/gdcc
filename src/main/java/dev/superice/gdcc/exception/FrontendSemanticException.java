package dev.superice.gdcc.exception;

import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnostic;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Exception thrown when frontend semantic analysis must fail-fast.
///
/// Kept in the shared exception package so semantic failures follow the same
/// repository-wide exception contract as backend and scope failures.
public final class FrontendSemanticException extends GdccException {
    private final @NotNull List<FrontendDiagnostic> diagnostics;

    public FrontendSemanticException(@NotNull String message, @NotNull List<FrontendDiagnostic> diagnostics) {
        super(message);
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
    }

    public @NotNull List<FrontendDiagnostic> diagnostics() {
        return diagnostics;
    }
}

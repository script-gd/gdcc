package dev.superice.gdcc.frontend.diagnostic;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

/// Unified diagnostic record for GDCC frontend parsing and semantic analysis.
public record FrontendDiagnostic(
        @NotNull FrontendDiagnosticSeverity severity,
        @NotNull String category,
        @NotNull String message,
        @Nullable Path sourcePath,
        @Nullable FrontendRange range
) {
    public FrontendDiagnostic {
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }

    public static @NotNull FrontendDiagnostic warning(
            @NotNull String category,
            @NotNull String message,
            @Nullable Path sourcePath,
            @Nullable FrontendRange range
    ) {
        return new FrontendDiagnostic(FrontendDiagnosticSeverity.WARNING, category, message, sourcePath, range);
    }

    public static @NotNull FrontendDiagnostic error(
            @NotNull String category,
            @NotNull String message,
            @Nullable Path sourcePath,
            @Nullable FrontendRange range
    ) {
        return new FrontendDiagnostic(FrontendDiagnosticSeverity.ERROR, category, message, sourcePath, range);
    }
}

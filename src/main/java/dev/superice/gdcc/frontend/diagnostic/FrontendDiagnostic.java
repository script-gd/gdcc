package dev.superice.gdcc.frontend.diagnostic;

import dev.superice.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

/// Unified diagnostic record for GDCC frontend parsing and semantic analysis.
public record FrontendDiagnostic(
        @NotNull FrontendDiagnosticSeverity severity,
        @NotNull String category,
        @NotNull String message,
        @Nullable String sourcePath,
        @Nullable FrontendRange range
) {
    public FrontendDiagnostic {
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(message, "message must not be null");
        sourcePath = normalizeSourcePath(sourcePath);
    }

    public static @NotNull FrontendDiagnostic warning(
            @NotNull String category,
            @NotNull String message,
            @Nullable String sourcePath,
            @Nullable FrontendRange range
    ) {
        return new FrontendDiagnostic(FrontendDiagnosticSeverity.WARNING, category, message, sourcePath, range);
    }

    public static @NotNull FrontendDiagnostic error(
            @NotNull String category,
            @NotNull String message,
            @Nullable String sourcePath,
            @Nullable FrontendRange range
    ) {
        return new FrontendDiagnostic(FrontendDiagnosticSeverity.ERROR, category, message, sourcePath, range);
    }

    /// Frontend diagnostics are display-facing. Host-native separators are normalized so API and
    /// tests can compare stable path text across platforms, and caller-provided labels such as
    /// `res://player.gd` pass through unchanged.
    public static @Nullable String sourcePathText(@Nullable Path sourcePath) {
        return sourcePath == null ? null : normalizeSourcePath(sourcePath.toString());
    }

    private static @Nullable String normalizeSourcePath(@Nullable String sourcePath) {
        if (sourcePath == null) {
            return null;
        }
        return StringUtil.requireTrimmedNonBlank(sourcePath, "sourcePath").replace('\\', '/');
    }
}

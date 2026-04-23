package dev.superice.gdcc.frontend.diagnostic;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/// Collects frontend diagnostics during one analysis pipeline.
///
/// This manager is intentionally small and frontend-specific:
/// - it owns the mutable diagnostic accumulation state for the current pipeline
/// - it exposes immutable snapshots at phase boundaries
/// - it does not attempt to deduplicate, sort, or reinterpret diagnostics
///
/// The immutable result objects in the frontend should continue to expose detached
/// diagnostics captured from this mutable manager at explicit phase boundaries.
public final class DiagnosticManager {
    private final @NotNull List<FrontendDiagnostic> diagnostics = new ArrayList<>();

    /// Appends one diagnostic to the current pipeline state.
    public void report(@NotNull FrontendDiagnostic diagnostic) {
        diagnostics.add(Objects.requireNonNull(diagnostic, "diagnostic must not be null"));
    }

    /// Appends a batch of diagnostics while validating the incoming collection shape.
    ///
    /// A `null` collection or `null` element is rejected so callers cannot silently
    /// corrupt later phase snapshots with partially-initialized diagnostic state.
    public void reportAll(@NotNull Collection<FrontendDiagnostic> diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        var validatedDiagnostics = new ArrayList<FrontendDiagnostic>(diagnostics.size());
        for (var diagnostic : diagnostics) {
            validatedDiagnostics.add(Objects.requireNonNull(diagnostic, "diagnostic must not be null"));
        }
        this.diagnostics.addAll(validatedDiagnostics);
    }

    /// Reports a warning without forcing callers to manually construct the record.
    public void warning(
            @NotNull String category,
            @NotNull String message,
            @Nullable Path sourcePath,
            @Nullable FrontendRange range
    ) {
        report(FrontendDiagnostic.warning(category, message, FrontendDiagnostic.sourcePathText(sourcePath), range));
    }

    /// Reports an error without forcing callers to manually construct the record.
    public void error(
            @NotNull String category,
            @NotNull String message,
            @Nullable Path sourcePath,
            @Nullable FrontendRange range
    ) {
        report(FrontendDiagnostic.error(category, message, FrontendDiagnostic.sourcePathText(sourcePath), range));
    }

    /// Returns whether any accumulated diagnostic is an error.
    ///
    /// This remains a derived query instead of cached state because the frontend still
    /// accumulates diagnostics in simple append-only order.
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == FrontendDiagnosticSeverity.ERROR);
    }

    /// Returns whether no diagnostics have been reported yet.
    public boolean isEmpty() {
        return diagnostics.isEmpty();
    }

    /// Exports an immutable snapshot of the current diagnostic state.
    ///
    /// The returned wrapper is detached from future mutations, so later reports do not
    /// rewrite diagnostics that were already published at an earlier phase boundary.
    public @NotNull DiagnosticSnapshot snapshot() {
        return new DiagnosticSnapshot(diagnostics);
    }
}

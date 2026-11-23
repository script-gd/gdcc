package gd.script.gdcc.frontend.diagnostic;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    private @Nullable Set<FrontendDiagnostic> exactDiagnostics;
    private @Nullable Map<SourceRange, Integer> firstErrorsByRange;
    private int indexedExactCount;
    private int indexedRangeCount;

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

    /// Checks the current manager for an exact diagnostic, including its severity and message.
    public boolean contains(@NotNull FrontendDiagnostic diagnostic) {
        var index = exactDiagnostics;
        if (index == null) {
            index = new HashSet<>();
            exactDiagnostics = index;
        }
        while (indexedExactCount < diagnostics.size()) {
            index.add(diagnostics.get(indexedExactCount++));
        }
        return index.contains(diagnostic);
    }

    /// Returns the first reported error at either exact range in the given source.
    /// A range's position in the argument list does not affect the result.
    public @Nullable FrontendDiagnostic firstErrorAtTwoRanges(
            @Nullable String sourcePath,
            @NotNull FrontendRange firstRange,
            @NotNull FrontendRange secondRange
    ) {
        var index = firstErrorsByRange;
        if (index == null) {
            index = new HashMap<>();
            firstErrorsByRange = index;
        }
        // The list is append-only. The first ordinal per range preserves report order across both
        // ranges without retaining a copy of each snapshot or changing what snapshots publish.
        while (indexedRangeCount < diagnostics.size()) {
            var ordinal = indexedRangeCount++;
            var diagnostic = diagnostics.get(ordinal);
            var range = diagnostic.range();
            if (diagnostic.severity() == FrontendDiagnosticSeverity.ERROR && range != null) {
                index.putIfAbsent(new SourceRange(diagnostic.sourcePath(), range), ordinal);
            }
        }
        var first = index.get(new SourceRange(sourcePath, firstRange));
        var second = index.get(new SourceRange(sourcePath, secondRange));
        if (first == null) {
            return second == null ? null : diagnostics.get(second);
        }
        return diagnostics.get(second == null ? first : Math.min(first, second));
    }

    /// Exports an immutable snapshot of the current diagnostic state.
    ///
    /// The returned wrapper is detached from future mutations, so later reports do not
    /// rewrite diagnostics that were already published at an earlier phase boundary.
    public @NotNull DiagnosticSnapshot snapshot() {
        return new DiagnosticSnapshot(diagnostics);
    }

    private record SourceRange(@Nullable String sourcePath, @NotNull FrontendRange range) {
    }
}

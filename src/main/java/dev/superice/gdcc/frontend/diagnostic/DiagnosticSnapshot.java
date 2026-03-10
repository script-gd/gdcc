package dev.superice.gdcc.frontend.diagnostic;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Immutable view of one `DiagnosticManager` state at a specific phase boundary.
///
/// This wrapper keeps the underlying diagnostics detached from later manager mutations while
/// still exposing a few high-signal queries, so callers do not need to immediately unwrap back
/// to a raw `List<FrontendDiagnostic>` just to ask basic questions.
public record DiagnosticSnapshot(@NotNull List<FrontendDiagnostic> diagnostics) {
    public DiagnosticSnapshot {
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
    }

    /// Returns the immutable diagnostics list for APIs that still store list-based results.
    public @NotNull List<FrontendDiagnostic> asList() {
        return diagnostics;
    }

    /// Returns whether this snapshot contains no diagnostics.
    public boolean isEmpty() {
        return diagnostics.isEmpty();
    }

    /// Returns the number of diagnostics captured in this snapshot.
    public int size() {
        return diagnostics.size();
    }

    /// Returns whether the captured diagnostics already contain an error.
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == FrontendDiagnosticSeverity.ERROR);
    }

    /// Returns the first diagnostic in insertion order.
    public @NotNull FrontendDiagnostic getFirst() {
        return diagnostics.getFirst();
    }

    /// Returns the last diagnostic in insertion order.
    public @NotNull FrontendDiagnostic getLast() {
        return diagnostics.getLast();
    }
}

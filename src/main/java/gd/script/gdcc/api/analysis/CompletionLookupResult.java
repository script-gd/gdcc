package gd.script.gdcc.api.analysis;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Result of `FrontendSnapshotCompletionService.completionCandidatesAt`
/// (`frontend_lsp_foundation_implementation.md` §2.5). The candidate list may be empty
/// (absent-result contract: unknown paths, failed units,
/// skipped subtrees and fact-free receivers all yield empty results, never exceptions).
///
/// @param contextKind      classified cursor context this answer was computed for
/// @param replaceableRange full identifier range to replace when a candidate is accepted
///                         (covers both sides of the cursor; zero-width at the cursor when no
///                         identifier is being typed); `null` when the display path is unknown
///                         to the snapshot or the coordinate cannot map into the source
/// @param candidates       candidate list in enumeration order (nearest scope first for identifiers);
///                    no presentation-ordering promise — sorting is the LSP layer's job
public record CompletionLookupResult(
        @NotNull CompletionContextKind contextKind,
        @Nullable QuerySourceRange replaceableRange,
        @NotNull List<CompletionCandidate> candidates
) {
    public CompletionLookupResult {
        Objects.requireNonNull(contextKind, "contextKind must not be null");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
    }
}

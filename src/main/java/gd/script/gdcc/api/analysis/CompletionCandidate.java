package gd.script.gdcc.api.analysis;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// One completion candidate projected from published analysis facts
/// (`frontend_lsp_foundation_implementation.md` §2.5). Candidates carry display data only;
/// filtering by the already-typed prefix and presentation
/// ordering are pushed down to the LSP layer, so this record makes no ordering promise.
///
/// @param name          candidate identifier text inserted at the replaceable range
/// @param kind          candidate category
/// @param typeText      display type name for `PROPERTY`/`VALUE` candidates (e.g. `Vector2`); `null`
///                 when the candidate has no meaningful type text (methods, types)
/// @param signatureText rendered `name(param: Type) -> Return` text for `METHOD` candidates;
///                      `null` for non-callable candidates
public record CompletionCandidate(
        @NotNull String name,
        @NotNull CompletionCandidateKind kind,
        @Nullable String typeText,
        @Nullable String signatureText
) {
    public CompletionCandidate {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
    }
}

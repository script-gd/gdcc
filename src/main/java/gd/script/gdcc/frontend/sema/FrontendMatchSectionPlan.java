package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.MatchSection;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Structural classification of one match section (pattern list + optional guard + body).
///
/// Patterns keep source order; the comma-separated pattern list is OR-semantics at runtime but the
/// plan records each pattern separately instead of merging them.
///
/// @param section  the `MatchSection` AST node; scope and diagnostic anchor identity. Its
///                 `patterns/guard/body` share one `BlockScope(MATCH_SECTION_BODY)` object
/// @param patterns per-pattern plans in source order
/// @param hasGuard whether the section has a `when` guard expression
public record FrontendMatchSectionPlan(
        @NotNull MatchSection section,
        @NotNull List<FrontendMatchPatternPlan> patterns,
        boolean hasGuard
) {
    public FrontendMatchSectionPlan {
        Objects.requireNonNull(section, "section must not be null");
        patterns = List.copyOf(Objects.requireNonNull(patterns, "patterns must not be null"));
    }
}

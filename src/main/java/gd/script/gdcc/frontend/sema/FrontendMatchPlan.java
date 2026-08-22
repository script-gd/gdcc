package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.MatchStatement;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Frozen semantic fact describing the structure of one `match` statement.
///
/// Published by the match pattern resolution owner into the `matchPlans()` side table (keyed by
/// `MatchStatement` identity, aligned with `forIterationPlans()`) and shared by type-check,
/// compile gate and CFG builder as the single source of route/binding truth. It intentionally
/// carries only structural classification and the bind inventory: no `GdCompilerType`, no
/// expression types, no intrinsic names and no lowering protocol.
///
/// @param statement owning `MatchStatement`; the side-table key, identity-equal across consumers
/// @param sections  per-section plans in source order; section order is the runtime test order
public record FrontendMatchPlan(
        @NotNull MatchStatement statement,
        @NotNull List<FrontendMatchSectionPlan> sections
) {
    public FrontendMatchPlan {
        Objects.requireNonNull(statement, "statement must not be null");
        sections = List.copyOf(Objects.requireNonNull(sections, "sections must not be null"));
    }

    /// Idempotent merge predicate for the `matchPlans()` side table.
    ///
    /// `statement` is ignored because the side table is keyed by `MatchStatement` identity.
    /// Section/pattern/binding lists are compared by AST identity and route, not structural equals.
    public static boolean samePlan(@NotNull FrontendMatchPlan first, @NotNull FrontendMatchPlan second) {
        Objects.requireNonNull(first, "first must not be null");
        Objects.requireNonNull(second, "second must not be null");
        if (first.sections().size() != second.sections().size()) {
            return false;
        }
        for (var i = 0; i < first.sections().size(); i++) {
            if (!sameSection(first.sections().get(i), second.sections().get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameSection(
            @NotNull FrontendMatchSectionPlan first,
            @NotNull FrontendMatchSectionPlan second
    ) {
        if (first.section() != second.section() || first.hasGuard() != second.hasGuard()) {
            return false;
        }
        if (first.patterns().size() != second.patterns().size()) {
            return false;
        }
        for (var i = 0; i < first.patterns().size(); i++) {
            if (!samePattern(first.patterns().get(i), second.patterns().get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean samePattern(
            @NotNull FrontendMatchPatternPlan first,
            @NotNull FrontendMatchPatternPlan second
    ) {
        if (first.patternNode() != second.patternNode() || first.route() != second.route()) {
            return false;
        }
        if (first.bindings().size() != second.bindings().size()) {
            return false;
        }
        for (var i = 0; i < first.bindings().size(); i++) {
            if (!sameBinding(first.bindings().get(i), second.bindings().get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameBinding(
            @NotNull FrontendMatchBindingPlan first,
            @NotNull FrontendMatchBindingPlan second
    ) {
        return first.declaration() == second.declaration()
                && first.topLevel() == second.topLevel()
                && first.name().equals(second.name());
    }
}

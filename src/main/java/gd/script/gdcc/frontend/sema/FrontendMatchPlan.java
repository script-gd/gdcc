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
}

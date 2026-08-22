package gd.script.gdcc.frontend.sema.patch;

import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendMatchPlan;
import gd.script.gdcc.frontend.sema.FrontendSemanticStage;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Match pattern resolution owner delta.
///
/// Publishes the `FrontendMatchPlan` side table and, separately, the restricted top-level bind
/// slot refinements keyed by `PatternBindingExpression`. Bind slot updates must never be folded
/// into `FrontendLocalTypeStabilizationPatch` or `FrontendForIterationResolutionPatch`: the three
/// owners own disjoint declaration-identity domains (`VariableDeclaration` versus `ForStatement`
/// versus `PatternBindingExpression`).
///
/// @param matchPlans           match plans keyed by the owning `MatchStatement`, merged into the
///                             stable `matchPlans()` side table; every plan is structural only
/// @param localSlotTypeUpdates restricted bind slot refinements whose declaration must be a
///                             `PatternBindingExpression`; each rewrites a top-level bind from
///                             `Variant` to the subject static type and never touches nested binds
///                             or ordinary `var :=` locals
public record FrontendMatchResolutionPatch(
        @NotNull FrontendAstSideTable<FrontendMatchPlan> matchPlans,
        @NotNull List<FrontendLocalSlotTypeUpdate> localSlotTypeUpdates
) implements FrontendOwnerPatch {
    public FrontendMatchResolutionPatch {
        matchPlans = FrontendPatchTables.copySideTable(matchPlans, "matchPlans");
        localSlotTypeUpdates = List.copyOf(Objects.requireNonNull(
                localSlotTypeUpdates,
                "localSlotTypeUpdates must not be null"
        ));
        FrontendPublishedFactTypeGuard.checkMatchPlans(matchPlans);
        FrontendPublishedFactTypeGuard.checkLocalSlotTypeUpdates(localSlotTypeUpdates);
    }

    @Override
    public @NotNull FrontendSemanticStage stage() {
        return FrontendSemanticStage.MATCH_PATTERN_RESOLUTION;
    }

    @Override
    public @NotNull FrontendAstSideTable<FrontendMatchPlan> matchPlans() {
        return matchPlans;
    }
}

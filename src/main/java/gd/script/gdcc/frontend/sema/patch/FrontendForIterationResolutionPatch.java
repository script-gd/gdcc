package gd.script.gdcc.frontend.sema.patch;

import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendForIterationPlan;
import gd.script.gdcc.frontend.sema.FrontendSemanticStage;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// For-iteration resolution owner delta.
///
/// Publishes the `FrontendForIterationPlan` side table and, separately, the restricted iterator slot
/// refinement keyed by the owning `ForStatement`. The iterator slot updates must never be folded into
/// `FrontendLocalTypeStabilizationPatch`: the two owners own disjoint declaration-identity domains
/// (`VariableDeclaration` versus `ForStatement`).
///
/// @param forIterationPlans iteration plans keyed by the owning `ForStatement`, merged into the stable
///                          `forIterationPlans()` side table; every plan is guard-checked to carry no
///                          compiler-only element type
/// @param localSlotTypeUpdates restricted iterator slot refinements whose declaration must be the owning
///                             `ForStatement`; each rewrites the iterator slot from `Variant` to the
///                             exposed element type and never touches ordinary `var :=` locals
public record FrontendForIterationResolutionPatch(
        @NotNull FrontendAstSideTable<FrontendForIterationPlan> forIterationPlans,
        @NotNull List<FrontendLocalSlotTypeUpdate> localSlotTypeUpdates
) implements FrontendOwnerPatch {
    public FrontendForIterationResolutionPatch {
        forIterationPlans = FrontendPatchTables.copySideTable(forIterationPlans, "forIterationPlans");
        localSlotTypeUpdates = List.copyOf(Objects.requireNonNull(
                localSlotTypeUpdates,
                "localSlotTypeUpdates must not be null"
        ));
        FrontendPublishedFactTypeGuard.checkForIterationPlans(forIterationPlans);
        FrontendPublishedFactTypeGuard.checkLocalSlotTypeUpdates(localSlotTypeUpdates);
    }

    @Override
    public @NotNull FrontendSemanticStage stage() {
        return FrontendSemanticStage.FOR_ITERATION_RESOLUTION;
    }
}

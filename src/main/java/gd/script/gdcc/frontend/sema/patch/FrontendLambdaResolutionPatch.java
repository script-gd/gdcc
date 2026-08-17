package gd.script.gdcc.frontend.sema.patch;

import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendLambdaPlan;
import gd.script.gdcc.frontend.sema.FrontendSemanticStage;
import org.jetbrains.annotations.NotNull;

/// Lambda resolution owner delta.
///
/// Publishes the first complete `FrontendLambdaPlan` for each nested-resolved `LambdaExpression`:
/// capture types are already the frozen declaration-site types and `capturesSelf` is aligned with
/// the leading `self` capture. The side table merge is first-wins with `samePlan` conflict
/// detection, so a second different payload for the same lambda fails instead of overwriting.
///
/// The plan must never be folded into `FrontendExprTypePatch`: it is keyed by the lambda's own
/// nested suite resolution and exports with the lambda's independent callable batch, before the
/// enclosing statement's expr-type owner could publish a callable type for the same node.
///
/// @param lambdaPlans plans keyed by `LambdaExpression` identity, merged into the stable
///                    `lambdaPlans()` side table; every plan is guard-checked to carry no
///                    compiler-only capture type
public record FrontendLambdaResolutionPatch(
        @NotNull FrontendAstSideTable<FrontendLambdaPlan> lambdaPlans
) implements FrontendOwnerPatch {
    public FrontendLambdaResolutionPatch {
        lambdaPlans = FrontendPatchTables.copySideTable(lambdaPlans, "lambdaPlans");
        FrontendPublishedFactTypeGuard.checkLambdaPlans(lambdaPlans);
    }

    @Override
    public @NotNull FrontendSemanticStage stage() {
        return FrontendSemanticStage.LAMBDA_RESOLUTION;
    }
}

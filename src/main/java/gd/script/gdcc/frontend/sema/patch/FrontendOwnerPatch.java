package gd.script.gdcc.frontend.sema.patch;

import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendBinding;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.frontend.sema.FrontendForIterationPlan;
import gd.script.gdcc.frontend.sema.FrontendResolvedCall;
import gd.script.gdcc.frontend.sema.FrontendResolvedMember;
import gd.script.gdcc.frontend.sema.FrontendSemanticStage;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/// Publication delta owned by exactly one semantic owner.
///
/// Suite export composes these patches in a `FrontendPatchTransaction`; it must not collapse facts
/// from multiple owners into one multi-owner payload.
public sealed interface FrontendOwnerPatch permits
        FrontendTopBindingPatch,
        FrontendLocalTypeStabilizationPatch,
        FrontendChainBindingPatch,
        FrontendExprTypePatch,
        FrontendForIterationResolutionPatch,
        FrontendVarTypePostPatch {
    @NotNull FrontendSemanticStage stage();

    default @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings() {
        return FrontendPatchTables.emptySideTable();
    }

    default @NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers() {
        return FrontendPatchTables.emptySideTable();
    }

    default @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls() {
        return FrontendPatchTables.emptySideTable();
    }

    default @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes() {
        return FrontendPatchTables.emptySideTable();
    }

    default @NotNull FrontendAstSideTable<GdType> slotTypes() {
        return FrontendPatchTables.emptySideTable();
    }

    default @NotNull FrontendAstSideTable<FrontendForIterationPlan> forIterationPlans() {
        return FrontendPatchTables.emptySideTable();
    }

    default @NotNull List<FrontendLocalSlotTypeUpdate> localSlotTypeUpdates() {
        return List.of();
    }
}

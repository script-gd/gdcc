package gd.script.gdcc.frontend.sema.patch;

import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendContainerLiteralPlan;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.frontend.sema.FrontendResolvedCall;
import gd.script.gdcc.frontend.sema.FrontendSemanticStage;
import gd.script.gdcc.frontend.sema.FrontendTypeTestTarget;
import org.jetbrains.annotations.NotNull;

/// Expression-typing owner delta. Publishes final expression facts, bare-call facts,
/// type-test targets, and container-literal plans.
public record FrontendExprTypePatch(
        @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes,
        @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls,
        @NotNull FrontendAstSideTable<FrontendTypeTestTarget> typeTestTargets,
        @NotNull FrontendAstSideTable<FrontendContainerLiteralPlan> containerLiteralPlans
) implements FrontendOwnerPatch {
    public FrontendExprTypePatch {
        expressionTypes = FrontendPatchTables.copySideTable(expressionTypes, "expressionTypes");
        resolvedCalls = FrontendPatchTables.copySideTable(resolvedCalls, "resolvedCalls");
        typeTestTargets = FrontendPatchTables.copySideTable(typeTestTargets, "typeTestTargets");
        containerLiteralPlans = FrontendPatchTables.copySideTable(
                containerLiteralPlans,
                "containerLiteralPlans"
        );
        FrontendPublishedFactTypeGuard.checkExpressionTypes(expressionTypes);
        FrontendPublishedFactTypeGuard.checkResolvedCalls(resolvedCalls);
        FrontendPublishedFactTypeGuard.checkTypeTestTargets(typeTestTargets);
        FrontendPublishedFactTypeGuard.checkContainerLiteralPlans(containerLiteralPlans);
    }

    /// Backward-compatible constructor for patches that do not publish container-literal plans.
    public FrontendExprTypePatch(
            @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes,
            @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls,
            @NotNull FrontendAstSideTable<FrontendTypeTestTarget> typeTestTargets
    ) {
        this(expressionTypes, resolvedCalls, typeTestTargets, FrontendPatchTables.emptySideTable());
    }

    /// Backward-compatible constructor for patches that only publish expression and call facts.
    public FrontendExprTypePatch(
            @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes,
            @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls
    ) {
        this(
                expressionTypes,
                resolvedCalls,
                FrontendPatchTables.emptySideTable(),
                FrontendPatchTables.emptySideTable()
        );
    }

    @Override
    public @NotNull FrontendSemanticStage stage() {
        return FrontendSemanticStage.EXPR_TYPE;
    }
}

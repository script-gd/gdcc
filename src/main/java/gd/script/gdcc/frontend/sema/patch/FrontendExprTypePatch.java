package gd.script.gdcc.frontend.sema.patch;

import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.frontend.sema.FrontendResolvedCall;
import gd.script.gdcc.frontend.sema.FrontendSemanticStage;
import gd.script.gdcc.frontend.sema.FrontendTypeTestTarget;
import org.jetbrains.annotations.NotNull;

/// Expression-typing owner delta. Publishes final expression facts, bare-call facts, and type-test targets.
public record FrontendExprTypePatch(
        @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes,
        @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls,
        @NotNull FrontendAstSideTable<FrontendTypeTestTarget> typeTestTargets
) implements FrontendOwnerPatch {
    public FrontendExprTypePatch {
        expressionTypes = FrontendPatchTables.copySideTable(expressionTypes, "expressionTypes");
        resolvedCalls = FrontendPatchTables.copySideTable(resolvedCalls, "resolvedCalls");
        typeTestTargets = FrontendPatchTables.copySideTable(typeTestTargets, "typeTestTargets");
        FrontendPublishedFactTypeGuard.checkExpressionTypes(expressionTypes);
        FrontendPublishedFactTypeGuard.checkResolvedCalls(resolvedCalls);
        FrontendPublishedFactTypeGuard.checkTypeTestTargets(typeTestTargets);
    }

    /// Backward-compatible constructor for patches that do not publish type-test targets.
    public FrontendExprTypePatch(
            @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes,
            @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls
    ) {
        this(expressionTypes, resolvedCalls, FrontendPatchTables.emptySideTable());
    }

    @Override
    public @NotNull FrontendSemanticStage stage() {
        return FrontendSemanticStage.EXPR_TYPE;
    }
}

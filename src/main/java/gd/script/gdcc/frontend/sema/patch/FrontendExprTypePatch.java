package gd.script.gdcc.frontend.sema.patch;

import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.frontend.sema.FrontendResolvedCall;
import gd.script.gdcc.frontend.sema.FrontendSemanticStage;
import org.jetbrains.annotations.NotNull;

/// Expression-typing owner delta. It publishes final expression facts and bare-call facts only.
public record FrontendExprTypePatch(
        @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes,
        @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls
) implements FrontendOwnerPatch {
    public FrontendExprTypePatch {
        expressionTypes = FrontendPatchTables.copySideTable(expressionTypes, "expressionTypes");
        resolvedCalls = FrontendPatchTables.copySideTable(resolvedCalls, "resolvedCalls");
        FrontendPublishedFactTypeGuard.checkExpressionTypes(expressionTypes);
        FrontendPublishedFactTypeGuard.checkResolvedCalls(resolvedCalls);
    }

    @Override
    public @NotNull FrontendSemanticStage stage() {
        return FrontendSemanticStage.EXPR_TYPE;
    }
}

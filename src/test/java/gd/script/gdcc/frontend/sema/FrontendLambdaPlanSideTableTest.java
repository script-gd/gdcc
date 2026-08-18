package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import gd.script.gdcc.exception.FrontendAnalysisPatchException;
import gd.script.gdcc.frontend.sema.patch.FrontendTopBindingPatch;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdccForRangeIterType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Lambda plans are a stable side table: compiler-only capture types stay rejected, and
/// existing owner patches do not wipe or republish the table.
class FrontendLambdaPlanSideTableTest {
    private static final Range RANGE = new Range(0, 1, new Point(0, 0), new Point(0, 1));

    @Test
    void updateLambdaPlansPublishesWithoutReplacingStableSideTableReference() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var stable = analysisData.lambdaPlans();
        var lambda = emptyLambda();
        var plan = samplePlan(lambda);
        var plans = new FrontendAstSideTable<FrontendLambdaPlan>();
        plans.put(lambda, plan);

        analysisData.updateLambdaPlans(plans);

        assertSame(stable, analysisData.lambdaPlans());
        assertSame(plan, analysisData.lambdaPlans().get(lambda));
    }

    @Test
    void updateLambdaPlansRejectsCompilerOnlyCaptureTypes() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var lambda = emptyLambda();
        var plans = new FrontendAstSideTable<FrontendLambdaPlan>();
        plans.put(lambda, new FrontendLambdaPlan(
                lambda,
                "_lambda_0",
                new FrontendLambdaCapturePlan(
                        List.of(new LambdaCaptureEntry(
                                "iter",
                                GdccForRangeIterType.FOR_RANGE_ITER,
                                ScopeValueKind.LOCAL,
                                new Object()
                        )),
                        false
                ),
                GdVariantType.VARIANT,
                emptyFunction(),
                "Hero"
        ));

        var exception = assertThrows(
                FrontendAnalysisPatchException.class,
                () -> analysisData.updateLambdaPlans(plans)
        );
        assertTrue(exception.getMessage().contains("compiler-only type"), exception.getMessage());
        assertTrue(analysisData.lambdaPlans().isEmpty());
    }

    @Test
    void applyPatchFromExistingOwnerDoesNotClearPublishedLambdaPlans() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var lambda = emptyLambda();
        var plan = samplePlan(lambda);
        var plans = new FrontendAstSideTable<FrontendLambdaPlan>();
        plans.put(lambda, plan);
        analysisData.updateLambdaPlans(plans);

        analysisData.applyPatch(new FrontendTopBindingPatch(new FrontendAstSideTable<>()));

        assertSame(plan, analysisData.lambdaPlans().get(lambda));
    }

    private static @NotNull FrontendLambdaPlan samplePlan(@NotNull LambdaExpression lambda) {
        return new FrontendLambdaPlan(
                lambda,
                "_lambda_0",
                new FrontendLambdaCapturePlan(
                        List.of(new LambdaCaptureEntry("seed", GdIntType.INT, ScopeValueKind.LOCAL, new Object())),
                        false
                ),
                GdVariantType.VARIANT,
                emptyFunction(),
                "Hero"
        );
    }

    private static @NotNull LambdaExpression emptyLambda() {
        return new LambdaExpression(null, List.of(), null, new Block(List.of(new PassStatement(RANGE)), RANGE), RANGE);
    }

    private static @NotNull FunctionDeclaration emptyFunction() {
        return new FunctionDeclaration(
                "run",
                List.of(),
                null,
                false,
                new Block(List.of(new PassStatement(RANGE)), RANGE),
                RANGE
        );
    }
}

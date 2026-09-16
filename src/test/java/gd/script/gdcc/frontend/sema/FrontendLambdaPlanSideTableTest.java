package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.Node;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    // ----- HR-8 sourceIdentityKey (hot_reload_implementation_plan.md §5.6 impl_key contract) -----

    private static @NotNull Range rangeAt(int startRow, int startColumn) {
        return new Range(0, 1, new Point(startRow, startColumn), new Point(startRow, startColumn + 1));
    }

    private static @NotNull LambdaExpression lambdaAt(int startRow, int startColumn) {
        var range = rangeAt(startRow, startColumn);
        return new LambdaExpression(null, List.of(), null, new Block(List.of(), range), range);
    }

    private static @NotNull FunctionDeclaration functionAt(@NotNull String name, int startRow) {
        var range = rangeAt(startRow, 0);
        return new FunctionDeclaration(name, List.of(), null, false, new Block(List.of(), range), range);
    }

    private static @NotNull FrontendLambdaPlan planOf(
            @NotNull LambdaExpression lambda,
            @NotNull Node enclosingCallable,
            @NotNull String owningClassCanonicalName
    ) {
        return new FrontendLambdaPlan(
                lambda,
                "_lambda_0",
                new FrontendLambdaCapturePlan(List.of(), false),
                GdVariantType.VARIANT,
                enclosingCallable,
                owningClassCanonicalName
        );
    }

    @Test
    void sourceIdentityKeyShouldUseRelativeLineOffsetAndOneBasedColumn() {
        // Lambda starts 2 rows and column 8 (0-based) after the enclosing function start:
        // the key anchors `<Class>::<func>@+<Δline>:<1-based col>` with no file name.
        var plan = planOf(lambdaAt(7, 8), functionAt("run", 5), "Hero");
        assertEquals("Hero::run@+2:9", plan.sourceIdentityKey());
    }

    @Test
    void sourceIdentityKeyShouldSurviveWholeFunctionMovesAndOuterFunctionEdits() {
        // The hot-reload main path: edits in OTHER functions (which shift every absolute row)
        // and moving the enclosing function as a whole must keep the key stable, because only
        // the RELATIVE offset inside the enclosing function feeds the key.
        var before = planOf(lambdaAt(7, 8), functionAt("run", 5), "Hero");
        var afterOuterEdits = planOf(lambdaAt(107, 8), functionAt("run", 105), "Hero");
        assertEquals(before.sourceIdentityKey(), afterOuterEdits.sourceIdentityKey());
    }

    @Test
    void sourceIdentityKeyShouldFailClosedWhenLambdaShiftsInsideItsFunction() {
        // Lines added/removed inside the SAME function before the lambda change the relative
        // offset: the key must NOT be reused (old connections invalidate instead of rebinding
        // to a wrong body).
        var before = planOf(lambdaAt(7, 8), functionAt("run", 5), "Hero");
        var shifted = planOf(lambdaAt(8, 8), functionAt("run", 5), "Hero");
        var movedColumn = planOf(lambdaAt(7, 9), functionAt("run", 5), "Hero");
        var otherFunction = planOf(lambdaAt(7, 8), functionAt("attack", 5), "Hero");
        var otherClass = planOf(lambdaAt(7, 8), functionAt("run", 5), "Villain");
        assertNotEquals(before.sourceIdentityKey(), shifted.sourceIdentityKey());
        assertNotEquals(before.sourceIdentityKey(), movedColumn.sourceIdentityKey());
        assertNotEquals(before.sourceIdentityKey(), otherFunction.sourceIdentityKey());
        assertNotEquals(before.sourceIdentityKey(), otherClass.sourceIdentityKey());
    }

    @Test
    void sourceIdentityKeyShouldRenderConstructorEnclosingAsInit() {
        var range = rangeAt(5, 0);
        var constructor = new ConstructorDeclaration(List.of(), null, new Block(List.of(), range), range);
        var plan = planOf(lambdaAt(9, 4), constructor, "Hero");
        assertEquals("Hero::_init@+4:5", plan.sourceIdentityKey());
    }
}

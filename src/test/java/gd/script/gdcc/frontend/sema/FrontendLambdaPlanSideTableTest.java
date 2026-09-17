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
                "Hero",
                0,
                "assign(var=cb, kind=var)"
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
                "Hero",
                0,
                "assign(var=cb, kind=var)"
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
            @NotNull String owningClassCanonicalName,
            int identityOrdinal
    ) {
        return new FrontendLambdaPlan(
                lambda,
                "_lambda_0",
                new FrontendLambdaCapturePlan(List.of(), false),
                GdVariantType.VARIANT,
                enclosingCallable,
                owningClassCanonicalName,
                identityOrdinal,
                "assign(var=cb, kind=var)"
        );
    }

    @Test
    void sourceIdentityKeyShouldUseSourceOrdinalWithinTheOutermostNamedFunction() {
        // §5.11 key format: `<Class>::<func>#<ordinal>` — no file name, no source position.
        var plan = planOf(lambdaAt(7, 8), functionAt("run", 5), "Hero", 0);
        assertEquals("Hero::run#0", plan.sourceIdentityKey());
    }

    @Test
    void sourceIdentityKeyShouldIgnoreSourcePositionsEntirely() {
        // The ordinal win: edits in OTHER functions, whole-function moves, and line/column
        // shifts inside the same function all keep the key — only inserting/removing a lambda
        // BEFORE this one inside the same function changes the ordinal.
        var before = planOf(lambdaAt(7, 8), functionAt("run", 5), "Hero", 0);
        var afterOuterEdits = planOf(lambdaAt(107, 1), functionAt("run", 105), "Hero", 0);
        assertEquals(before.sourceIdentityKey(), afterOuterEdits.sourceIdentityKey());
    }

    @Test
    void sourceIdentityKeyShouldFailClosedWhenOrdinalOrOwnerChanges() {
        // A lambda inserted before this one shifts its ordinal; moving to another function or
        // class changes the owner path: the key must NOT be reused (old connections invalidate
        // instead of rebinding to a wrong body unless schema + context also collide).
        var before = planOf(lambdaAt(7, 8), functionAt("run", 5), "Hero", 0);
        var shiftedOrdinal = planOf(lambdaAt(7, 8), functionAt("run", 5), "Hero", 1);
        var otherFunction = planOf(lambdaAt(7, 8), functionAt("attack", 5), "Hero", 0);
        var otherClass = planOf(lambdaAt(7, 8), functionAt("run", 5), "Villain", 0);
        assertNotEquals(before.sourceIdentityKey(), shiftedOrdinal.sourceIdentityKey());
        assertNotEquals(before.sourceIdentityKey(), otherFunction.sourceIdentityKey());
        assertNotEquals(before.sourceIdentityKey(), otherClass.sourceIdentityKey());
    }

    @Test
    void sourceIdentityKeyShouldRenderConstructorEnclosingAsInit() {
        var range = rangeAt(5, 0);
        var constructor = new ConstructorDeclaration(List.of(), null, new Block(List.of(), range), range);
        var plan = planOf(lambdaAt(9, 4), constructor, "Hero", 2);
        assertEquals("Hero::_init#2", plan.sourceIdentityKey());
    }

    @Test
    void constructorShouldRejectNegativeOrdinalAndBlankContext() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendLambdaPlan(
                        lambdaAt(7, 8),
                        "_lambda_0",
                        new FrontendLambdaCapturePlan(List.of(), false),
                        GdVariantType.VARIANT,
                        functionAt("run", 5),
                        "Hero",
                        -1,
                        "stmt(ExpressionStatement)"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendLambdaPlan(
                        lambdaAt(7, 8),
                        "_lambda_0",
                        new FrontendLambdaCapturePlan(List.of(), false),
                        GdVariantType.VARIANT,
                        functionAt("run", 5),
                        "Hero",
                        0,
                        "  "
                )
        );
    }
}

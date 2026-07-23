package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.TypeRef;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Anchors `FrontendForLoopSupport` route classification and plan construction to the plan document:
/// bare `range(...)` is recognized purely by AST callee shape, int shorthand keeps only the stop
/// operand, and unknown iterables fall back to generic Variant without faking AST.
class FrontendForLoopSupportTest {
    private static final Range RANGE = new Range(0, 1, new Point(0, 0), new Point(0, 1));

    @Test
    void classifiesBareRangeCallAsRangeCallAndPreservesSourceArguments() {
        var stop = intLiteral("3");
        var rangeCall = bareRangeCall(List.of(stop));
        var statement = forStatement("i", null, rangeCall);

        var plan = FrontendForLoopSupport.buildPlan(statement, null, null);

        assertEquals(FrontendForIterationRoute.RANGE_CALL, plan.route());
        assertEquals("i", plan.iteratorName());
        assertNull(plan.declaredIteratorType());
        assertSame(GdIntType.INT, plan.rawElementType());
        assertSame(GdIntType.INT, plan.exposedIteratorType());
        assertFalse(plan.requiresPerElementConversion());
        assertEquals(1, plan.sourceOperands().size());
        assertSame(stop, plan.sourceOperands().getFirst());
    }

    @Test
    void rangeCallPlanPreservesAllArgumentsInSourceOrder() {
        var start = intLiteral("1");
        var end = intLiteral("5");
        var step = intLiteral("2");
        var rangeCall = bareRangeCall(List.of(start, end, step));
        var statement = forStatement("i", null, rangeCall);

        var plan = FrontendForLoopSupport.buildPlan(statement, null, null);

        assertEquals(FrontendForIterationRoute.RANGE_CALL, plan.route());
        assertEquals(List.of(start, end, step), plan.sourceOperands());
    }

    @Test
    void doesNotClassifyNonBareRangeFormsAsRangeCall() {
        // some_range(3): callee identifier with a different name must not trigger the range pre-route.
        var someRangeCall = new CallExpression(
                new IdentifierExpression("some_range", RANGE),
                List.of(intLiteral("3")),
                RANGE
        );
        var someRangePlan = FrontendForLoopSupport.buildPlan(forStatement("i", null, someRangeCall), null, null);
        assertEquals(FrontendForIterationRoute.GENERIC_VARIANT, someRangePlan.route());

        // obj.range(3): attribute callee is not a bare identifier and must not trigger the pre-route.
        var attributeCallee = new AttributeExpression(new IdentifierExpression("obj", RANGE), List.of(), RANGE);
        var attributeCall = new CallExpression(attributeCallee, List.of(intLiteral("3")), RANGE);
        var attributePlan = FrontendForLoopSupport.buildPlan(forStatement("i", null, attributeCall), null, null);
        assertEquals(FrontendForIterationRoute.GENERIC_VARIANT, attributePlan.route());

        // `for i in range:` (bare identifier, not a call) must not trigger the pre-route either.
        var bareIdentifier = new IdentifierExpression("range", RANGE);
        var bareIdentifierPlan = FrontendForLoopSupport.buildPlan(forStatement("i", null, bareIdentifier), null, null);
        assertEquals(FrontendForIterationRoute.GENERIC_VARIANT, bareIdentifierPlan.route());
    }

    @Test
    void classifiesIntIterableAsIntShorthandKeepingOnlyStopOperand() {
        var limit = new IdentifierExpression("limit", RANGE);
        var statement = forStatement("i", null, limit);

        var plan = FrontendForLoopSupport.buildPlan(statement, null, GdIntType.INT);

        assertEquals(FrontendForIterationRoute.INT_SHORTHAND, plan.route());
        assertSame(GdIntType.INT, plan.rawElementType());
        assertSame(GdIntType.INT, plan.exposedIteratorType());
        assertFalse(plan.requiresPerElementConversion());
        // The shorthand must not fabricate `0` / `1` AST nodes; only the stop expression is kept.
        assertEquals(1, plan.sourceOperands().size());
        assertSame(limit, plan.sourceOperands().getFirst());
    }

    @Test
    void fallsBackToGenericVariantForUnknownOrVariantIterable() {
        var values = new IdentifierExpression("values", RANGE);

        var unknownPlan = FrontendForLoopSupport.buildPlan(forStatement("item", null, values), null, null);
        assertEquals(FrontendForIterationRoute.GENERIC_VARIANT, unknownPlan.route());
        assertSame(GdVariantType.VARIANT, unknownPlan.rawElementType());
        assertSame(GdVariantType.VARIANT, unknownPlan.exposedIteratorType());
        assertFalse(unknownPlan.requiresPerElementConversion());
        assertEquals(1, unknownPlan.sourceOperands().size());
        assertSame(values, unknownPlan.sourceOperands().getFirst());

        var variantPlan = FrontendForLoopSupport.buildPlan(
                forStatement("item", null, values),
                null,
                GdVariantType.VARIANT
        );
        assertEquals(FrontendForIterationRoute.GENERIC_VARIANT, variantPlan.route());
        assertSame(GdVariantType.VARIANT, variantPlan.rawElementType());

        // Float shorthand is not specialized yet, so a float iterable stays on the generic route.
        var floatPlan = FrontendForLoopSupport.buildPlan(
                forStatement("item", null, values),
                null,
                GdFloatType.FLOAT
        );
        assertEquals(FrontendForIterationRoute.GENERIC_VARIANT, floatPlan.route());
        assertSame(GdVariantType.VARIANT, floatPlan.rawElementType());
    }

    @Test
    void declaredIteratorTypeWinsAndRecordsPerElementConversionWhenDifferentFromRaw() {
        var rangeCall = bareRangeCall(List.of(intLiteral("3")));

        var floatPlan = FrontendForLoopSupport.buildPlan(
                forStatement("i", new TypeRef("float", RANGE), rangeCall),
                GdFloatType.FLOAT,
                null
        );
        assertEquals(FrontendForIterationRoute.RANGE_CALL, floatPlan.route());
        assertSame(GdIntType.INT, floatPlan.rawElementType());
        assertSame(GdFloatType.FLOAT, floatPlan.exposedIteratorType());
        assertTrue(floatPlan.requiresPerElementConversion());

        var intPlan = FrontendForLoopSupport.buildPlan(
                forStatement("i", new TypeRef("int", RANGE), rangeCall),
                GdIntType.INT,
                null
        );
        assertSame(GdIntType.INT, intPlan.exposedIteratorType());
        assertFalse(intPlan.requiresPerElementConversion());
    }

    @Test
    void isBareRangeCallMatchesGodotAstShapeWithoutNameResolution() {
        assertTrue(FrontendForLoopSupport.isBareRangeCall(bareRangeCall(List.of(intLiteral("3")))));
        assertFalse(FrontendForLoopSupport.isBareRangeCall(
                new CallExpression(new IdentifierExpression("some_range", RANGE), List.of(intLiteral("3")), RANGE)
        ));
        assertFalse(FrontendForLoopSupport.isBareRangeCall(new IdentifierExpression("range", RANGE)));
        assertFalse(FrontendForLoopSupport.isBareRangeCall(
                new CallExpression(
                        new AttributeExpression(new IdentifierExpression("obj", RANGE), List.of(), RANGE),
                        List.of(intLiteral("3")),
                        RANGE
                )
        ));
    }

    private static @NotNull LiteralExpression intLiteral(@NotNull String text) {
        return new LiteralExpression("int", text, RANGE);
    }

    private static @NotNull CallExpression bareRangeCall(@NotNull List<Expression> arguments) {
        return new CallExpression(new IdentifierExpression("range", RANGE), arguments, RANGE);
    }

    private static @NotNull ForStatement forStatement(
            @NotNull String iterator,
            @Nullable TypeRef iteratorType,
            @NotNull Expression iterable
    ) {
        var body = new Block(List.of(new PassStatement(RANGE)), RANGE);
        return new ForStatement(iterator, iteratorType, iterable, body, RANGE);
    }
}

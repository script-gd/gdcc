package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdVariantType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrontendExpressionTypeTest {
    @Test
    void resolvedFactoryPublishesExactTypeWithoutDetailReason() {
        var expressionType = FrontendExpressionType.resolved(GdIntType.INT);

        assertEquals(FrontendExpressionTypeStatus.RESOLVED, expressionType.status());
        assertEquals(GdIntType.INT, expressionType.publishedType());
        assertNull(expressionType.detailReason());
    }

    @Test
    void blockedFactoryAllowsWinnerTypeWhenRestrictionAlreadyFoundTarget() {
        var expressionType = FrontendExpressionType.blocked(GdIntType.INT, "value is blocked in static context");

        assertEquals(FrontendExpressionTypeStatus.BLOCKED, expressionType.status());
        assertEquals(GdIntType.INT, expressionType.publishedType());
        assertEquals("value is blocked in static context", expressionType.detailReason());
    }

    @Test
    void blockedFactoryAlsoAllowsTypeLessBlockedDependency() {
        var expressionType = FrontendExpressionType.blocked(null, "blocked dependency has no stable winner type");

        assertEquals(FrontendExpressionTypeStatus.BLOCKED, expressionType.status());
        assertNull(expressionType.publishedType());
        assertEquals("blocked dependency has no stable winner type", expressionType.detailReason());
    }

    @Test
    void dynamicFactoryPinsPublishedTypeToVariant() {
        var expressionType = FrontendExpressionType.dynamic("runtime-dynamic fallback");

        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, expressionType.status());
        assertEquals(GdVariantType.VARIANT, expressionType.publishedType());
        assertEquals("runtime-dynamic fallback", expressionType.detailReason());
    }

    @Test
    void constructorRejectsNonVariantDynamicPublication() {
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendExpressionType(
                        FrontendExpressionTypeStatus.DYNAMIC,
                        GdIntType.INT,
                        "not variant"
                )
        );

        assertEquals("dynamic expression types must publish Variant", ex.getMessage());
    }

    @Test
    void constructorRejectsPublishedTypeOnDeferredFailureLikeStatuses() {
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> new FrontendExpressionType(
                        FrontendExpressionTypeStatus.DEFERRED,
                        GdVariantType.VARIANT,
                        "still waiting"
                )
        );

        assertEquals("publishedType must be null for DEFERRED expression types", ex.getMessage());
    }
}

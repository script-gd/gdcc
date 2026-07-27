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
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdCallableType;
import gd.script.gdcc.type.GdColorType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdIntVectorType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdNodePathType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdPackedNumericArrayType;
import gd.script.gdcc.type.GdPackedStringArrayType;
import gd.script.gdcc.type.GdPackedVectorArrayType;
import gd.script.gdcc.type.GdRect2Type;
import gd.script.gdcc.type.GdRidType;
import gd.script.gdcc.type.GdSignalType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        assertNull(plan.declaredIteratorTypeRef());
        assertSame(GdIntType.INT, plan.semanticElementType());
        assertSame(GdIntType.INT, plan.exposedIteratorType());
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
        assertSame(GdIntType.INT, plan.semanticElementType());
        assertSame(GdIntType.INT, plan.exposedIteratorType());
        // The shorthand must not fabricate `0` / `1` AST nodes; only the stop expression is kept.
        assertEquals(1, plan.sourceOperands().size());
        assertSame(limit, plan.sourceOperands().getFirst());
    }

    @Test
    void fallsBackToGenericVariantForUnknownOrVariantIterable() {
        var values = new IdentifierExpression("values", RANGE);

        var unknownPlan = FrontendForLoopSupport.buildPlan(forStatement("item", null, values), null, null);
        assertEquals(FrontendForIterationRoute.GENERIC_VARIANT, unknownPlan.route());
        assertSame(GdVariantType.VARIANT, unknownPlan.semanticElementType());
        assertSame(GdVariantType.VARIANT, unknownPlan.exposedIteratorType());
        assertEquals(1, unknownPlan.sourceOperands().size());
        assertSame(values, unknownPlan.sourceOperands().getFirst());

        var variantPlan = FrontendForLoopSupport.buildPlan(
                forStatement("item", null, values),
                null,
                GdVariantType.VARIANT
        );
        assertEquals(FrontendForIterationRoute.GENERIC_VARIANT, variantPlan.route());
        assertSame(GdVariantType.VARIANT, variantPlan.semanticElementType());

        var floatPlan = FrontendForLoopSupport.buildPlan(
                forStatement("item", null, values),
                null,
                GdFloatType.FLOAT
        );
        assertEquals(FrontendForIterationRoute.FLOAT_SHORTHAND, floatPlan.route());
        assertSame(GdFloatType.FLOAT, floatPlan.semanticElementType());
    }

    @Test
    void declaredIteratorTypeWinsWithoutEmbeddingLoweringConversionState() {
        var rangeCall = bareRangeCall(List.of(intLiteral("3")));

        var floatPlan = FrontendForLoopSupport.buildPlan(
                forStatement("i", new TypeRef("float", RANGE), rangeCall),
                GdFloatType.FLOAT,
                null
        );
        assertEquals(FrontendForIterationRoute.RANGE_CALL, floatPlan.route());
        assertSame(GdIntType.INT, floatPlan.semanticElementType());
        assertSame(GdFloatType.FLOAT, floatPlan.exposedIteratorType());

        var intPlan = FrontendForLoopSupport.buildPlan(
                forStatement("i", new TypeRef("int", RANGE), rangeCall),
                GdIntType.INT,
                null
        );
        assertSame(GdIntType.INT, intPlan.exposedIteratorType());
    }

    @Test
    void resolvesSemanticElementTypesForKnownIterableFamilies() {
        var iterable = new IdentifierExpression("values", RANGE);

        assertPlanSemanticElement(iterable, GdStringType.STRING, GdStringType.STRING, FrontendForIterationRoute.STRING);
        assertPlanSemanticElement(iterable, new GdArrayType(GdIntType.INT), GdIntType.INT, FrontendForIterationRoute.ARRAY);
        assertPlanSemanticElement(
                iterable,
                new GdDictionaryType(GdStringType.STRING, GdIntType.INT),
                GdStringType.STRING,
                FrontendForIterationRoute.DICTIONARY_KEYS
        );
        assertPlanSemanticElement(
                iterable,
                GdPackedNumericArrayType.PACKED_FLOAT64_ARRAY,
                GdFloatType.FLOAT,
                FrontendForIterationRoute.PACKED_FLOAT64_ARRAY
        );
        assertPlanSemanticElement(
                iterable,
                GdFloatType.FLOAT,
                GdFloatType.FLOAT,
                FrontendForIterationRoute.FLOAT_SHORTHAND
        );
    }

    @Test
    void resolvesVariantSemanticElementForUntypedContainersAndDynamicTypes() {
        var iterable = new IdentifierExpression("values", RANGE);

        assertPlanSemanticElement(iterable, new GdArrayType(GdVariantType.VARIANT), GdVariantType.VARIANT,
                FrontendForIterationRoute.ARRAY);
        assertPlanSemanticElement(
                iterable,
                new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT),
                GdVariantType.VARIANT,
                FrontendForIterationRoute.DICTIONARY_KEYS
        );
        assertPlanSemanticElement(iterable, new GdObjectType("Object"), GdVariantType.VARIANT);
        assertPlanSemanticElement(iterable, GdVariantType.VARIANT, GdVariantType.VARIANT);
    }

    @Test
    void classifiesIterableTypesWithTheirGodotElementTypes() {
        assertStaticElement(GdIntType.INT, GdIntType.INT);
        assertStaticElement(GdFloatType.FLOAT, GdFloatType.FLOAT);
        assertStaticElement(GdStringType.STRING, GdStringType.STRING);
        assertStaticElement(GdFloatVectorType.VECTOR2, GdFloatType.FLOAT);
        assertStaticElement(GdFloatVectorType.VECTOR3, GdFloatType.FLOAT);
        assertStaticElement(GdIntVectorType.VECTOR2I, GdIntType.INT);
        assertStaticElement(GdIntVectorType.VECTOR3I, GdIntType.INT);
        assertStaticElement(new GdArrayType(GdIntType.INT), GdIntType.INT);
        assertStaticElement(new GdArrayType(GdVariantType.VARIANT), GdVariantType.VARIANT);
        assertStaticElement(
                new GdDictionaryType(GdStringType.STRING, GdIntType.INT),
                GdStringType.STRING
        );
        assertStaticElement(
                new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT),
                GdVariantType.VARIANT
        );
        assertStaticElement(GdPackedNumericArrayType.PACKED_INT32_ARRAY, GdIntType.INT);
        assertStaticElement(GdPackedNumericArrayType.PACKED_FLOAT32_ARRAY, GdFloatType.FLOAT);
        assertStaticElement(GdPackedStringArrayType.PACKED_STRING_ARRAY, GdStringType.STRING);
        assertStaticElement(GdPackedVectorArrayType.PACKED_VECTOR3_ARRAY, GdFloatVectorType.VECTOR3);
    }

    @Test
    void classifiesNonIterableHardTypesWithoutAStaticElement() {
        List<GdType> nonIterableTypes = List.of(
                GdBoolType.BOOL,
                GdNilType.NIL,
                new GdCallableType(),
                new GdSignalType(),
                GdRidType.RID,
                GdStringNameType.STRING_NAME,
                GdNodePathType.NODE_PATH,
                GdFloatVectorType.VECTOR4,
                GdIntVectorType.VECTOR4I,
                GdRect2Type.RECT2,
                GdColorType.COLOR,
                GdccForRangeIterType.FOR_RANGE_ITER
        );

        for (var iterableType : nonIterableTypes) {
            var semantics = Objects.requireNonNull(assertInstanceOf(
                    FrontendIterableSemantics.NonIterable.class,
                    FrontendForLoopSupport.classifyIterableSemantics(iterableType)
            ));
            assertSame(iterableType, semantics.iterableType());
        }
    }

    @Test
    void classifiesVariantAndObjectAsDynamicAndUnknownAsUnresolved() {
        assertInstanceOf(
                FrontendIterableSemantics.DynamicIterable.class,
                FrontendForLoopSupport.classifyIterableSemantics(GdVariantType.VARIANT)
        );
        assertInstanceOf(
                FrontendIterableSemantics.DynamicIterable.class,
                FrontendForLoopSupport.classifyIterableSemantics(new GdObjectType("Object"))
        );
        assertNull(FrontendForLoopSupport.classifyIterableSemantics(null));
    }

    @Test
    void explicitVariantDeclarationPreservesVariantAcrossAllCurrentRoutes() {
        var variantTypeRef = new TypeRef("Variant", RANGE);
        var rangePlan = FrontendForLoopSupport.buildPlan(
                forStatement("i", variantTypeRef, bareRangeCall(List.of(intLiteral("3")))),
                GdVariantType.VARIANT,
                null
        );
        var shorthandPlan = FrontendForLoopSupport.buildPlan(
                forStatement("i", variantTypeRef, new IdentifierExpression("limit", RANGE)),
                GdVariantType.VARIANT,
                GdIntType.INT
        );
        var genericPlan = FrontendForLoopSupport.buildPlan(
                forStatement("i", variantTypeRef, new IdentifierExpression("values", RANGE)),
                GdVariantType.VARIANT,
                null
        );

        for (var plan : List.of(rangePlan, shorthandPlan, genericPlan)) {
            assertSame(variantTypeRef, plan.declaredIteratorTypeRef());
            assertSame(GdVariantType.VARIANT, plan.exposedIteratorType());
        }
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

    private static void assertPlanSemanticElement(
            @NotNull Expression iterable,
            @NotNull GdType iterableType,
            @NotNull GdType expectedElementType
    ) {
        assertPlanSemanticElement(iterable, iterableType, expectedElementType, FrontendForIterationRoute.GENERIC_VARIANT);
    }

    private static void assertPlanSemanticElement(
            @NotNull Expression iterable,
            @NotNull GdType iterableType,
            @NotNull GdType expectedElementType,
            @NotNull FrontendForIterationRoute expectedRoute
    ) {
        var plan = FrontendForLoopSupport.buildPlan(forStatement("item", null, iterable), null, iterableType);
        assertEquals(expectedRoute, plan.route());
        assertSame(expectedElementType, plan.semanticElementType());
        assertSame(expectedElementType, plan.exposedIteratorType());
    }

    private static void assertStaticElement(@NotNull GdType iterableType, @NotNull GdType expectedElementType) {
        var semantics = Objects.requireNonNull(assertInstanceOf(
                FrontendIterableSemantics.StaticIterable.class,
                FrontendForLoopSupport.classifyIterableSemantics(iterableType)
        ));
        assertSame(expectedElementType, semantics.elementType());
    }
}

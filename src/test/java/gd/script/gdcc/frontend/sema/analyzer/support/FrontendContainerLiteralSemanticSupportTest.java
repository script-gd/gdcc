package gd.script.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.DictEntry;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import gd.script.gdcc.frontend.sema.FrontendContainerLiteralPlan;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Unit contracts for generic and contextual container-literal shared semantic.
class FrontendContainerLiteralSemanticSupportTest {
    private static final @NotNull Range TINY = new Range(0, 1, new Point(0, 0), new Point(0, 1));

    @Test
    void resolveArrayPublishesGenericArrayPlanWithoutInferringElementType() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var array = new ArrayExpression(
                List.of(integerLiteral("1"), stringLiteral("\"two\"")),
                false,
                TINY
        );

        var resolution = FrontendContainerLiteralSemanticSupport.resolveArrayExpressionType(
                registry,
                array,
                resolvedChildren(),
                true
        );

        assertTrue(resolution.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, resolution.expressionType().status());
        assertEquals("Array", resolution.expressionType().publishedType().getTypeName());
        assertInstanceOf(GdArrayType.class, resolution.expressionType().publishedType());
        assertTrue(((GdArrayType) resolution.expressionType().publishedType()).isGenericArray());

        assertNotNull(resolution.planOrNull());
        var plan = resolution.planOrNull();
        assertEquals(2, plan.operands().size());
        assertEquals(FrontendContainerLiteralPlan.OperandRole.ARRAY_ELEMENT, plan.operands().getFirst().role());
        assertEquals(GdIntType.INT, plan.operands().getFirst().sourceType());
        assertEquals(GdVariantType.VARIANT, plan.operands().getFirst().targetType());
        assertEquals(
                FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_PACK,
                plan.operands().getFirst().decision()
        );
        assertTrue(plan.duplicateKeyIssues().isEmpty());
    }

    @Test
    void resolveEmptyArrayAndDictionaryAreResolvedGenericContainers() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var emptyArray = FrontendContainerLiteralSemanticSupport.resolveArrayExpressionType(
                registry,
                new ArrayExpression(List.of(), false, TINY),
                resolvedChildren(),
                true
        );
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, emptyArray.expressionType().status());
        assertEquals("Array", emptyArray.expressionType().publishedType().getTypeName());
        assertNotNull(emptyArray.planOrNull());
        assertTrue(emptyArray.planOrNull().operands().isEmpty());

        var emptyDict = FrontendContainerLiteralSemanticSupport.resolveDictionaryExpressionType(
                registry,
                new DictionaryExpression(List.of(), false, TINY),
                resolvedChildren(),
                true
        );
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, emptyDict.expressionType().status());
        assertEquals("Dictionary", emptyDict.expressionType().publishedType().getTypeName());
        assertNotNull(emptyDict.planOrNull());
        assertTrue(emptyDict.planOrNull().operands().isEmpty());
    }

    @Test
    void resolveDictionaryPublishesGenericPlanAndKeyValueOperandOrder() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var dictionary = new DictionaryExpression(
                List.of(new DictEntry(stringLiteral("\"hp\""), integerLiteral("100"), TINY)),
                false,
                TINY
        );

        var resolution = FrontendContainerLiteralSemanticSupport.resolveDictionaryExpressionType(
                registry,
                dictionary,
                resolvedChildren(),
                true
        );

        assertEquals(FrontendExpressionTypeStatus.RESOLVED, resolution.expressionType().status());
        assertEquals("Dictionary", resolution.expressionType().publishedType().getTypeName());
        assertInstanceOf(GdDictionaryType.class, resolution.expressionType().publishedType());
        assertTrue(((GdDictionaryType) resolution.expressionType().publishedType()).isGenericDictionary());

        assertNotNull(resolution.planOrNull());
        var plan = resolution.planOrNull();
        assertEquals(2, plan.operands().size());
        assertEquals(FrontendContainerLiteralPlan.OperandRole.DICTIONARY_KEY, plan.operands().getFirst().role());
        assertEquals(FrontendContainerLiteralPlan.OperandRole.DICTIONARY_VALUE, plan.operands().getLast().role());
        assertEquals(0, plan.operands().getFirst().sourceIndex());
        assertEquals(0, plan.operands().getLast().sourceIndex());
        assertEquals(GdStringType.STRING, plan.operands().getFirst().sourceType());
        assertEquals(GdIntType.INT, plan.operands().getLast().sourceType());
    }

    @Test
    void openEndedArrayAndDictionaryFailClosedWithoutPlan() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var arrayResolution = FrontendContainerLiteralSemanticSupport.resolveArrayExpressionType(
                registry,
                new ArrayExpression(List.of(integerLiteral("1")), true, TINY),
                resolvedChildren(),
                true
        );
        assertTrue(arrayResolution.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, arrayResolution.expressionType().status());
        assertTrue(arrayResolution.expressionType().detailReason().contains("open-ending"));
        assertNull(arrayResolution.planOrNull());

        var dictResolution = FrontendContainerLiteralSemanticSupport.resolveDictionaryExpressionType(
                registry,
                new DictionaryExpression(List.of(), true, TINY),
                resolvedChildren(),
                true
        );
        assertTrue(dictResolution.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, dictResolution.expressionType().status());
        assertNull(dictResolution.planOrNull());
    }

    @Test
    void childFailurePropagatesWithoutRootOwnedOutcomeOrPlan() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var array = new ArrayExpression(List.of(identifier("missing"), integerLiteral("2")), false, TINY);

        var resolution = FrontendContainerLiteralSemanticSupport.resolveArrayExpressionType(
                registry,
                array,
                (expression, finalizeWindow) -> expression instanceof IdentifierExpression
                        ? FrontendExpressionType.failed("identifier missing")
                        : FrontendExpressionType.resolved(GdIntType.INT),
                true
        );

        assertFalse(resolution.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, resolution.expressionType().status());
        assertTrue(resolution.expressionType().detailReason().contains("identifier missing"));
        assertNull(resolution.planOrNull());
    }

    @Test
    void deferredAndBlockedChildrenPropagateWithoutPlan() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var deferred = FrontendContainerLiteralSemanticSupport.resolveArrayExpressionType(
                registry,
                new ArrayExpression(List.of(identifier("deferred_child")), false, TINY),
                (expression, finalizeWindow) -> FrontendExpressionType.deferred("child deferred"),
                true
        );
        assertFalse(deferred.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.DEFERRED, deferred.expressionType().status());
        assertNull(deferred.planOrNull());

        var blocked = FrontendContainerLiteralSemanticSupport.resolveDictionaryExpressionType(
                registry,
                new DictionaryExpression(
                        List.of(new DictEntry(identifier("blocked_key"), integerLiteral("1"), TINY)),
                        false,
                        TINY
                ),
                (expression, finalizeWindow) -> expression instanceof IdentifierExpression
                        ? FrontendExpressionType.blocked(null, "child blocked")
                        : FrontendExpressionType.resolved(GdIntType.INT),
                true
        );
        assertFalse(blocked.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.BLOCKED, blocked.expressionType().status());
        assertNull(blocked.planOrNull());
    }

    @Test
    void nestedArrayLiteralResolvesThroughNestedResolver() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var inner = new ArrayExpression(List.of(integerLiteral("1")), false, TINY);
        var outer = new ArrayExpression(List.of(inner, integerLiteral("2")), false, TINY);

        var resolution = FrontendContainerLiteralSemanticSupport.resolveArrayExpressionType(
                registry,
                outer,
                (expression, finalizeWindow) -> {
                    if (expression instanceof ArrayExpression nested) {
                        return FrontendContainerLiteralSemanticSupport.resolveArrayExpressionType(
                                registry,
                                nested,
                                resolvedChildren(),
                                finalizeWindow
                        ).expressionType();
                    }
                    return resolvedChildren().resolve(expression, finalizeWindow);
                },
                true
        );

        assertEquals(FrontendExpressionTypeStatus.RESOLVED, resolution.expressionType().status());
        assertNotNull(resolution.planOrNull());
        assertEquals(2, resolution.planOrNull().operands().size());
        assertEquals("Array", resolution.planOrNull().operands().getFirst().sourceType().getTypeName());
    }

    @Test
    void dictionaryDuplicateConstantKeysAreFrozenAsPlanIssues() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        // String / StringName share string-like key equivalence; int 1 and float 1.0 stay distinct.
        var dictionary = new DictionaryExpression(
                List.of(
                        new DictEntry(stringLiteral("\"hp\""), integerLiteral("1"), TINY),
                        new DictEntry(stringNameLiteral("&\"hp\""), integerLiteral("2"), TINY),
                        new DictEntry(integerLiteral("1"), integerLiteral("3"), TINY),
                        new DictEntry(floatLiteral("1.0"), integerLiteral("4"), TINY)
                ),
                false,
                TINY
        );

        var resolution = FrontendContainerLiteralSemanticSupport.resolveDictionaryExpressionType(
                registry,
                dictionary,
                resolvedChildren(),
                true
        );
        assertNotNull(resolution.planOrNull());
        var plan = resolution.planOrNull();
        assertEquals(1, plan.duplicateKeyIssues().size());
        var issue = plan.duplicateKeyIssues().getFirst();
        assertEquals(0, issue.firstEntryIndex());
        assertEquals(1, issue.duplicateEntryIndex());
        assertEquals("\"hp\"", issue.keyDisplay());
    }

    @Test
    void dictionaryDuplicateDetectionUsesDecodedStringAndOctalIntegerValues() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var dictionary = new DictionaryExpression(
                List.of(
                        // "\u0068p" and "hp" are the same runtime string key after unescape.
                        new DictEntry(stringLiteral("\"\\u0068p\""), integerLiteral("1"), TINY),
                        new DictEntry(stringLiteral("\"hp\""), integerLiteral("2"), TINY),
                        // 0o17 == 15 decimal; both must collide as int keys.
                        new DictEntry(integerLiteral("0o17"), integerLiteral("3"), TINY),
                        new DictEntry(integerLiteral("15"), integerLiteral("4"), TINY)
                ),
                false,
                TINY
        );

        var plan = FrontendContainerLiteralSemanticSupport.resolveDictionaryExpressionType(
                registry,
                dictionary,
                resolvedChildren(),
                true
        ).planOrNull();
        assertNotNull(plan);
        assertEquals(2, plan.duplicateKeyIssues().size());
        assertEquals(0, plan.duplicateKeyIssues().getFirst().firstEntryIndex());
        assertEquals(1, plan.duplicateKeyIssues().getFirst().duplicateEntryIndex());
        assertEquals(2, plan.duplicateKeyIssues().getLast().firstEntryIndex());
        assertEquals(3, plan.duplicateKeyIssues().getLast().duplicateEntryIndex());
    }

    @Test
    void unsupportedIntegerLexemeDoesNotCrashAndIsNotReducible() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        // Overflow / malformed int keys stay non-reducible instead of throwing from semantic analysis.
        var dictionary = new DictionaryExpression(
                List.of(
                        new DictEntry(integerLiteral("999999999999999999999"), integerLiteral("1"), TINY),
                        new DictEntry(integerLiteral("999999999999999999999"), integerLiteral("2"), TINY)
                ),
                false,
                TINY
        );

        var resolution = FrontendContainerLiteralSemanticSupport.resolveDictionaryExpressionType(
                registry,
                dictionary,
                resolvedChildren(),
                true
        );
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, resolution.expressionType().status());
        assertNotNull(resolution.planOrNull());
        assertTrue(resolution.planOrNull().duplicateKeyIssues().isEmpty());
    }

    @Test
    void dynamicChildIsTypingStableAndStillPublishesPlan() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var array = new ArrayExpression(List.of(identifier("dynamic"), integerLiteral("2")), false, TINY);

        var resolution = FrontendContainerLiteralSemanticSupport.resolveArrayExpressionType(
                registry,
                array,
                (expression, finalizeWindow) -> expression instanceof IdentifierExpression
                        ? FrontendExpressionType.dynamic("runtime-open")
                        : FrontendExpressionType.resolved(GdIntType.INT),
                true
        );

        assertTrue(resolution.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, resolution.expressionType().status());
        assertNotNull(resolution.planOrNull());
        assertEquals(2, resolution.planOrNull().operands().size());
        assertEquals(GdVariantType.VARIANT, resolution.planOrNull().operands().getFirst().sourceType());
    }

    @Test
    void contextualArrayIntUsesDirectBoundaryAndResultType() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var array = new ArrayExpression(List.of(integerLiteral("1"), integerLiteral("2")), false, TINY);

        var resolution = FrontendContainerLiteralSemanticSupport.resolveArrayExpressionType(
                registry,
                array,
                contextualResolvedChildren(),
                true,
                new GdArrayType(GdIntType.INT)
        );

        assertEquals("Array[int]", resolution.expressionType().publishedType().getTypeName());
        assertNotNull(resolution.planOrNull());
        assertEquals(
                FrontendVariantBoundaryCompatibility.Decision.ALLOW_DIRECT,
                resolution.planOrNull().operands().getFirst().decision()
        );
        assertEquals(GdIntType.INT, resolution.planOrNull().operands().getFirst().targetType());
    }

    @Test
    void contextualArrayFloatFromIntUsesIntrinsicCast() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var array = new ArrayExpression(List.of(integerLiteral("1"), integerLiteral("2")), false, TINY);

        var resolution = FrontendContainerLiteralSemanticSupport.resolveArrayExpressionType(
                registry,
                array,
                contextualResolvedChildren(),
                true,
                new GdArrayType(GdFloatType.FLOAT)
        );

        assertEquals("Array[float]", resolution.expressionType().publishedType().getTypeName());
        assertNotNull(resolution.planOrNull());
        assertEquals(2, resolution.planOrNull().operands().size());
        for (var operand : resolution.planOrNull().operands()) {
            assertEquals(FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_INTRINSIC_CAST, operand.decision());
            assertEquals(GdFloatType.FLOAT, operand.targetType());
        }
    }

    @Test
    void contextualArrayStringNameFromStringUsesBuiltinConstructor() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var array = new ArrayExpression(List.of(stringLiteral("\"x\"")), false, TINY);

        var resolution = FrontendContainerLiteralSemanticSupport.resolveArrayExpressionType(
                registry,
                array,
                contextualResolvedChildren(),
                true,
                new GdArrayType(GdStringNameType.STRING_NAME)
        );

        assertEquals("Array[StringName]", resolution.expressionType().publishedType().getTypeName());
        assertNotNull(resolution.planOrNull());
        assertEquals(
                FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_BUILTIN_CONSTRUCTOR,
                resolution.planOrNull().operands().getFirst().decision()
        );
    }

    @Test
    void contextualArrayStringFromIntIsRejectInPlan() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var array = new ArrayExpression(List.of(integerLiteral("1")), false, TINY);

        var resolution = FrontendContainerLiteralSemanticSupport.resolveArrayExpressionType(
                registry,
                array,
                contextualResolvedChildren(),
                true,
                new GdArrayType(GdStringType.STRING)
        );

        // Root keeps contextual type; type-check owns the REJECT diagnostic.
        assertEquals("Array[String]", resolution.expressionType().publishedType().getTypeName());
        assertNotNull(resolution.planOrNull());
        assertEquals(
                FrontendVariantBoundaryCompatibility.Decision.REJECT,
                resolution.planOrNull().operands().getFirst().decision()
        );
    }

    @Test
    void nonArrayExpectedTypeDoesNotRewriteFamily() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var array = new ArrayExpression(List.of(integerLiteral("1")), false, TINY);

        var resolution = FrontendContainerLiteralSemanticSupport.resolveArrayExpressionType(
                registry,
                array,
                contextualResolvedChildren(),
                true,
                GdStringType.STRING
        );

        assertEquals("Array", resolution.expressionType().publishedType().getTypeName());
        assertTrue(((GdArrayType) resolution.expressionType().publishedType()).isGenericArray());
    }

    @Test
    void nestedTypedArrayConstructionFailsClosedWithoutPlan() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var array = new ArrayExpression(List.of(), false, TINY);

        var resolution = FrontendContainerLiteralSemanticSupport.resolveArrayExpressionType(
                registry,
                array,
                contextualResolvedChildren(),
                true,
                new GdArrayType(new GdArrayType(GdIntType.INT))
        );

        assertEquals(FrontendExpressionTypeStatus.FAILED, resolution.expressionType().status());
        assertTrue(resolution.expressionType().detailReason().contains("Nested typed container"));
        assertNull(resolution.planOrNull());
    }

    @Test
    void nestedGenericArrayElementIsAllowed() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var array = new ArrayExpression(List.of(), false, TINY);

        var resolution = FrontendContainerLiteralSemanticSupport.resolveArrayExpressionType(
                registry,
                array,
                contextualResolvedChildren(),
                true,
                new GdArrayType(new GdArrayType(GdVariantType.VARIANT))
        );

        assertEquals(FrontendExpressionTypeStatus.RESOLVED, resolution.expressionType().status());
        assertEquals("Array[Array]", resolution.expressionType().publishedType().getTypeName());
        assertNotNull(resolution.planOrNull());
    }

    @Test
    void contextualDictionaryFreezesKeyConstructorAndValueIntrinsic() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var dict = new DictionaryExpression(
                List.of(new DictEntry(stringLiteral("\"x\""), integerLiteral("1"), TINY)),
                false,
                TINY
        );

        var resolution = FrontendContainerLiteralSemanticSupport.resolveDictionaryExpressionType(
                registry,
                dict,
                contextualResolvedChildren(),
                true,
                new GdDictionaryType(GdStringNameType.STRING_NAME, GdFloatType.FLOAT)
        );

        assertEquals("Dictionary[StringName, float]", resolution.expressionType().publishedType().getTypeName());
        assertNotNull(resolution.planOrNull());
        assertEquals(2, resolution.planOrNull().operands().size());
        assertEquals(
                FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_BUILTIN_CONSTRUCTOR,
                resolution.planOrNull().operands().getFirst().decision()
        );
        assertEquals(
                FrontendVariantBoundaryCompatibility.Decision.ALLOW_WITH_INTRINSIC_CAST,
                resolution.planOrNull().operands().get(1).decision()
        );
    }

    @Test
    void rankLiteralAgainstTypedArrayRejectsIncompatibleElements() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var array = new ArrayExpression(List.of(integerLiteral("1")), false, TINY);

        var accepted = FrontendContainerLiteralSemanticSupport.rankLiteralAgainstParameter(
                registry,
                array,
                List.of(GdIntType.INT),
                new GdArrayType(GdIntType.INT)
        );
        assertFalse(accepted.rejected());

        var rejected = FrontendContainerLiteralSemanticSupport.rankLiteralAgainstParameter(
                registry,
                array,
                List.of(GdIntType.INT),
                new GdArrayType(GdStringType.STRING)
        );
        assertTrue(rejected.rejected());
    }

    private static @NotNull FrontendExpressionSemanticSupport.NestedExpressionResolver resolvedChildren() {
        return (expression, finalizeWindow) -> {
            if (expression instanceof LiteralExpression literal) {
                return switch (literal.kind()) {
                    case "integer", "number" -> FrontendExpressionType.resolved(GdIntType.INT);
                    case "float" -> FrontendExpressionType.resolved(GdFloatType.FLOAT);
                    case "string" -> FrontendExpressionType.resolved(GdStringType.STRING);
                    case "string_name" -> FrontendExpressionType.resolved(GdStringNameType.STRING_NAME);
                    default -> FrontendExpressionType.resolved(GdVariantType.VARIANT);
                };
            }
            return FrontendExpressionType.resolved(GdVariantType.VARIANT);
        };
    }

    private static @NotNull FrontendExpressionSemanticSupport.ContextualNestedExpressionResolver contextualResolvedChildren() {
        return (expression, finalizeWindow, expectedType) -> resolvedChildren().resolve(expression, finalizeWindow);
    }

    private static @NotNull LiteralExpression integerLiteral(@NotNull String sourceText) {
        return new LiteralExpression("integer", sourceText, TINY);
    }

    private static @NotNull LiteralExpression floatLiteral(@NotNull String sourceText) {
        return new LiteralExpression("float", sourceText, TINY);
    }

    private static @NotNull LiteralExpression stringLiteral(@NotNull String sourceText) {
        return new LiteralExpression("string", sourceText, TINY);
    }

    private static @NotNull LiteralExpression stringNameLiteral(@NotNull String sourceText) {
        return new LiteralExpression("string_name", sourceText, TINY);
    }

    private static @NotNull IdentifierExpression identifier(@NotNull String name) {
        return new IdentifierExpression(name, TINY);
    }
}

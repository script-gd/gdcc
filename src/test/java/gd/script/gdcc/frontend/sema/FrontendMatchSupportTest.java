package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.DictEntry;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MatchSection;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Anchors `FrontendMatchSupport` pattern classification, bind collection and route readiness:
/// the seven pattern shapes classify to their routes, bare identifiers are expression patterns
/// (never bindings or wildcards), nested binds are collected in source order with cleared
/// `topLevel`, dictionary keys never bind, and all six routes are lowering-ready.
class FrontendMatchSupportTest {
    private static final Range RANGE = new Range(0, 1, new Point(0, 0), new Point(0, 1));

    @Test
    void classifiesLiteralPatternFamilyAsLiteralRoute() {
        for (var kind : List.of("integer", "float", "string", "string_name", "true", "false", "null")) {
            assertEquals(
                    FrontendMatchPatternRoute.LITERAL,
                    FrontendMatchSupport.classifyPatternRoute(new LiteralExpression(kind, "text", RANGE)),
                    "kind " + kind
            );
        }
    }

    @Test
    void classifiesUnderscoreIdentifierAsWildcardOnlyByName() {
        assertEquals(
                FrontendMatchPatternRoute.WILDCARD,
                FrontendMatchSupport.classifyPatternRoute(new IdentifierExpression("_", RANGE))
        );
    }

    @Test
    void classifiesPatternBindingExpressionAsBindingRoute() {
        assertEquals(
                FrontendMatchPatternRoute.BINDING,
                FrontendMatchSupport.classifyPatternRoute(new PatternBindingExpression("value", RANGE))
        );
    }

    @Test
    void doesNotClassifyBareIdentifierAsWildcardOrBinding() {
        // Negative anchor: only the exact name `_` is a wildcard, and only `var name`
        // (PatternBindingExpression) is a binding; a bare identifier is an expression pattern,
        // regardless of whether it names a constant or a runtime value.
        assertEquals(
                FrontendMatchPatternRoute.EXPRESSION,
                FrontendMatchSupport.classifyPatternRoute(new IdentifierExpression("MY_CONST", RANGE))
        );
        assertEquals(
                FrontendMatchPatternRoute.EXPRESSION,
                FrontendMatchSupport.classifyPatternRoute(new IdentifierExpression("value", RANGE))
        );
    }

    @Test
    void classifiesAttributeChainAndOtherExpressionsAsExpression() {
        var chain = new AttributeExpression(
                new IdentifierExpression("Variant", RANGE),
                List.of(new AttributePropertyStep("Type", RANGE), new AttributePropertyStep("TYPE_NIL", RANGE)),
                RANGE
        );
        assertEquals(FrontendMatchPatternRoute.EXPRESSION, FrontendMatchSupport.classifyPatternRoute(chain));

        var call = new CallExpression(new IdentifierExpression("compute", RANGE), List.of(), RANGE);
        assertEquals(FrontendMatchPatternRoute.EXPRESSION, FrontendMatchSupport.classifyPatternRoute(call));
    }

    @Test
    void classifiesContainerPatternsAsArrayAndDictionaryRoutes() {
        var array = new ArrayExpression(List.of(intLiteral("1")), false, RANGE);
        assertEquals(FrontendMatchPatternRoute.ARRAY, FrontendMatchSupport.classifyPatternRoute(array));

        var dictionary = new DictionaryExpression(List.of(), false, RANGE);
        assertEquals(FrontendMatchPatternRoute.DICTIONARY, FrontendMatchSupport.classifyPatternRoute(dictionary));
    }

    @Test
    void buildPlanPreservesStatementSectionAndPatternIdentityInSourceOrder() {
        var subject = new IdentifierExpression("x", RANGE);
        var literalPattern = intLiteral("1");
        var wildcardPattern = new IdentifierExpression("_", RANGE);
        var firstSection = section(List.of(literalPattern), null);
        var secondSection = section(List.of(wildcardPattern), intLiteral("0"));
        var statement = new MatchStatement(subject, List.of(firstSection, secondSection), RANGE);

        var plan = FrontendMatchSupport.buildPlan(statement);

        assertSame(statement, plan.statement());
        assertEquals(2, plan.sections().size());

        var firstPlan = plan.sections().getFirst();
        assertSame(firstSection, firstPlan.section());
        assertEquals(1, firstPlan.patterns().size());
        assertSame(literalPattern, firstPlan.patterns().getFirst().patternNode());
        assertEquals(FrontendMatchPatternRoute.LITERAL, firstPlan.patterns().getFirst().route());
        assertFalse(firstPlan.hasGuard());

        var secondPlan = plan.sections().getLast();
        assertSame(secondSection, secondPlan.section());
        assertSame(wildcardPattern, secondPlan.patterns().getFirst().patternNode());
        assertEquals(FrontendMatchPatternRoute.WILDCARD, secondPlan.patterns().getFirst().route());
        assertTrue(secondPlan.hasGuard());
    }

    @Test
    void buildPlanKeepsMultiPatternListAsSeparatePlansInSourceOrder() {
        var first = intLiteral("1");
        var second = intLiteral("2");
        var third = new PatternBindingExpression("rest", RANGE);
        var statement = matchStatement(section(List.of(first, second, third), null));

        var plan = FrontendMatchSupport.buildPlan(statement);

        var patterns = plan.sections().getFirst().patterns();
        assertEquals(3, patterns.size());
        assertSame(first, patterns.get(0).patternNode());
        assertSame(second, patterns.get(1).patternNode());
        assertSame(third, patterns.get(2).patternNode());
        assertEquals(FrontendMatchPatternRoute.LITERAL, patterns.get(0).route());
        assertEquals(FrontendMatchPatternRoute.LITERAL, patterns.get(1).route());
        assertEquals(FrontendMatchPatternRoute.BINDING, patterns.get(2).route());
    }

    @Test
    void buildPlanMarksTopLevelBindingWithTopLevelFlagAndIdentity() {
        var binding = new PatternBindingExpression("value", RANGE);
        var statement = matchStatement(section(List.of(binding), null));

        var plan = FrontendMatchSupport.buildPlan(statement);

        var bindings = plan.sections().getFirst().patterns().getFirst().bindings();
        assertEquals(1, bindings.size());
        assertEquals("value", bindings.getFirst().name());
        assertSame(binding, bindings.getFirst().declaration());
        assertTrue(bindings.getFirst().topLevel());
    }

    @Test
    void buildPlanCollectsNestedBindingsInSourceOrderWithoutTopLevel() {
        var headBind = new PatternBindingExpression("head", RANGE);
        var innerBind = new PatternBindingExpression("inner", RANGE);
        var valueBind = new PatternBindingExpression("value", RANGE);
        var deepBind = new PatternBindingExpression("deep", RANGE);
        var arrayPattern = new ArrayExpression(
                List.of(
                        headBind,
                        new ArrayExpression(List.of(innerBind), false, RANGE)
                ),
                true,
                RANGE
        );
        var dictionaryPattern = new DictionaryExpression(
                List.of(
                        new DictEntry(stringLiteral("\"k\""), valueBind, RANGE),
                        new DictEntry(
                                stringLiteral("\"outer\""),
                                new DictionaryExpression(
                                        List.of(new DictEntry(stringLiteral("\"inner\""), deepBind, RANGE)),
                                        false,
                                        RANGE
                                ),
                                RANGE
                        )
                ),
                false,
                RANGE
        );
        var statement = matchStatement(section(List.of(arrayPattern, dictionaryPattern), null));

        var plan = FrontendMatchSupport.buildPlan(statement);

        var arrayBindings = plan.sections().getFirst().patterns().getFirst().bindings();
        assertEquals(2, arrayBindings.size());
        assertSame(headBind, arrayBindings.get(0).declaration());
        assertSame(innerBind, arrayBindings.get(1).declaration());
        assertFalse(arrayBindings.get(0).topLevel());
        assertFalse(arrayBindings.get(1).topLevel());

        var dictionaryBindings = plan.sections().getFirst().patterns().get(1).bindings();
        assertEquals(2, dictionaryBindings.size());
        assertSame(valueBind, dictionaryBindings.get(0).declaration());
        assertSame(deepBind, dictionaryBindings.get(1).declaration());
        assertFalse(dictionaryBindings.get(0).topLevel());
        assertFalse(dictionaryBindings.get(1).topLevel());
    }

    @Test
    void collectPatternBindingsSkipsDictionaryKeysBecauseKeysAreConstantExpressions() {
        // Negative anchor: Godot parses dictionary pattern keys as constant expressions, never as
        // patterns, so a key-position bind must not enter the bind inventory.
        var keyBind = new PatternBindingExpression("key", RANGE);
        var valueBind = new PatternBindingExpression("value", RANGE);
        var dictionary = new DictionaryExpression(
                List.of(new DictEntry(keyBind, valueBind, RANGE)),
                false,
                RANGE
        );

        var bindings = FrontendMatchSupport.collectPatternBindings(dictionary, true);

        assertEquals(1, bindings.size());
        assertSame(valueBind, bindings.getFirst().declaration());
        assertFalse(bindings.getFirst().topLevel());
    }

    @Test
    void collectPatternBindingsReturnsEmptyListForBindFreePatterns() {
        assertTrue(FrontendMatchSupport.collectPatternBindings(intLiteral("1"), true).isEmpty());
        assertTrue(FrontendMatchSupport.collectPatternBindings(new IdentifierExpression("_", RANGE), true).isEmpty());
        assertTrue(FrontendMatchSupport.collectPatternBindings(
                new ArrayExpression(List.of(intLiteral("1")), true, RANGE),
                true
        ).isEmpty());
    }

    @Test
    void samePlanTreatsIdentityEqualPlansAsIdempotentAndDivergentRoutesAsConflict() {
        var statement = matchStatement(section(List.of(intLiteral("1")), null));
        var first = FrontendMatchSupport.buildPlan(statement);
        var second = FrontendMatchSupport.buildPlan(statement);
        assertTrue(FrontendMatchPlan.samePlan(first, second));

        var divergent = new FrontendMatchPlan(
                statement,
                List.of(new FrontendMatchSectionPlan(
                        statement.sections().getFirst(),
                        List.of(new FrontendMatchPatternPlan(
                                statement.sections().getFirst().patterns().getFirst(),
                                FrontendMatchPatternRoute.EXPRESSION,
                                List.of()
                        )),
                        false
                ))
        );
        assertFalse(FrontendMatchPlan.samePlan(first, divergent));
    }

    @Test
    void allSixRoutesAreLoweringReady() {
        // All six pattern routes are currently compile-ready.
        for (var route : FrontendMatchPatternRoute.values()) {
            assertTrue(FrontendMatchSupport.isRouteLoweringReady(route), route::toString);
        }
    }

    private static @NotNull MatchStatement matchStatement(@NotNull MatchSection... sections) {
        return new MatchStatement(new IdentifierExpression("x", RANGE), List.of(sections), RANGE);
    }

    private static @NotNull MatchSection section(@NotNull List<Expression> patterns, @Nullable Expression guard) {
        var body = new Block(List.of(new PassStatement(RANGE)), RANGE);
        return new MatchSection(patterns, guard, body, RANGE);
    }

    private static @NotNull LiteralExpression intLiteral(@NotNull String text) {
        return new LiteralExpression("integer", text, RANGE);
    }

    private static @NotNull LiteralExpression stringLiteral(@NotNull String text) {
        return new LiteralExpression("string", text, RANGE);
    }
}

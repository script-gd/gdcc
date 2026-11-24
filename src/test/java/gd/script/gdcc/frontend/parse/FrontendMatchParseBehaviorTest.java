package gd.script.gdcc.frontend.parse;

import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MatchSection;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import dev.superice.gdparser.frontend.gdscript.DiagnosticCodes;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Freezes the match-pattern AST contracts used by match lowering.
///
/// Scope is parser shape only: match sections and patterns, trailing rest, key-only dictionary
/// entries, and recovery for misplaced rest markers.
/// Semantic classification stays in `FrontendMatchSupportTest`.
class FrontendMatchParseBehaviorTest {
    private final GdScriptParserService parserService = new GdScriptParserService();

    @Test
    void matchStatementMapsSubjectSectionsPatternsAndBodies() {
        var unit = parse("match_basic.gd", """
                func probe(x):
                    match x:
                        1:
                            pass
                        var bound:
                            pass
                """);

        var match = matchFromFunction(unit, "probe");
        assertEquals("x", assertInstanceOf(IdentifierExpression.class, match.value()).name());
        assertEquals(2, match.sections().size());

        var first = match.sections().getFirst();
        assertEquals(1, first.patterns().size());
        assertLiteralKind(first.patterns().getFirst(), "integer");
        assertNull(first.guard());
        assertNotNull(first.body());

        var second = match.sections().getLast();
        assertEquals("bound", assertInstanceOf(PatternBindingExpression.class, second.patterns().getFirst()).name());
        assertNull(second.guard());
    }

    @Test
    void literalPatternFamilyKeepsLiteralKinds() {
        var unit = parse("match_literals.gd", """
                func probe(x):
                    match x:
                        1:
                            pass
                        1.5:
                            pass
                        "a":
                            pass
                        &"s":
                            pass
                        true:
                            pass
                        null:
                            pass
                """);

        var match = matchFromFunction(unit, "probe");
        assertEquals(6, match.sections().size());
        assertLiteralKind(match.sections().get(0).patterns().getFirst(), "integer");
        assertLiteralKind(match.sections().get(1).patterns().getFirst(), "float");
        assertLiteralKind(match.sections().get(2).patterns().getFirst(), "string");
        assertLiteralKind(match.sections().get(3).patterns().getFirst(), "string_name");
        assertLiteralKind(match.sections().get(4).patterns().getFirst(), "true");
        assertLiteralKind(match.sections().get(5).patterns().getFirst(), "null");
    }

    @Test
    void wildcardPatternMapsToUnderscoreIdentifier() {
        // gdparser has no dedicated wildcard node; `_` must be recognized by name in pattern context.
        var unit = parse("match_wildcard.gd", """
                func probe(x):
                    match x:
                        _:
                            pass
                """);

        var match = matchFromFunction(unit, "probe");
        assertEquals("_", assertInstanceOf(IdentifierExpression.class, match.sections().getFirst().patterns().getFirst()).name());
    }

    @Test
    void bareIdentifierAndAttributeChainPatternsStayOrdinaryExpressions() {
        var unit = parse("match_constant_expressions.gd", """
                func probe(x):
                    match x:
                        TYPE_ARRAY:
                            pass
                        Variant.Type.TYPE_NIL:
                            pass
                """);

        var match = matchFromFunction(unit, "probe");
        var bare = match.sections().get(0).patterns().getFirst();
        assertEquals("TYPE_ARRAY", assertInstanceOf(IdentifierExpression.class, bare).name());

        var chain = match.sections().get(1).patterns().getFirst();
        var attribute = assertInstanceOf(AttributeExpression.class, chain);
        assertEquals("Variant", assertInstanceOf(IdentifierExpression.class, attribute.base()).name());
        assertEquals(2, attribute.steps().size());
    }

    @Test
    void multiplePatternsInSectionFormPatternList() {
        var unit = parse("match_multi_pattern.gd", """
                func probe(x):
                    match x:
                        1, 2, 3:
                            pass
                """);

        var match = matchFromFunction(unit, "probe");
        var section = match.sections().getFirst();
        assertEquals(3, section.patterns().size());
        assertLiteralKind(section.patterns().get(0), "integer");
        assertLiteralKind(section.patterns().get(1), "integer");
        assertLiteralKind(section.patterns().get(2), "integer");
    }

    @Test
    void guardExpressionMapsToNullableSectionGuard() {
        var unit = parse("match_guard.gd", """
                func probe(x):
                    match x:
                        var v when v > 0:
                            pass
                        _:
                            pass
                """);

        var match = matchFromFunction(unit, "probe");
        var guarded = match.sections().getFirst();
        assertNotNull(guarded.guard());
        assertNull(match.sections().getLast().guard());
    }

    @Test
    void arrayPatternPreservesElementsNestedBindAndOpenEnded() {
        var unit = parse("match_array_pattern.gd", """
                func probe(x):
                    match x:
                        [1, var head, _, ..]:
                            pass
                """);

        var match = matchFromFunction(unit, "probe");
        var array = assertInstanceOf(ArrayExpression.class, match.sections().getFirst().patterns().getFirst());
        assertTrue(array.openEnded());
        assertEquals(3, array.elements().size());
        assertLiteralKind(array.elements().get(0), "integer");
        assertEquals("head", assertInstanceOf(PatternBindingExpression.class, array.elements().get(1)).name());
        assertEquals("_", assertInstanceOf(IdentifierExpression.class, array.elements().getLast()).name());
    }

    @Test
    void dictionaryPatternWithWildcardValueParses() {
        var unit = parse("match_dict_wildcard_value.gd", """
                func probe(x):
                    match x:
                        {"k": _}:
                            pass
                """);

        var match = matchFromFunction(unit, "probe");
        var dictionary = assertInstanceOf(DictionaryExpression.class, match.sections().getFirst().patterns().getFirst());
        assertFalse(dictionary.openEnded());
        assertEquals(1, dictionary.entries().size());
        assertStringLiteral(dictionary.entries().getFirst().key(), "\"k\"");
        assertEquals("_", assertInstanceOf(IdentifierExpression.class, dictionary.entries().getFirst().value()).name());
    }

    @Test
    void nestedDictionaryPatternPreservesInnerBind() {
        var unit = parse("match_dict_nested.gd", """
                func probe(x):
                    match x:
                        {"user": {"name": var n}}:
                            pass
                """);

        var match = matchFromFunction(unit, "probe");
        var outer = assertInstanceOf(DictionaryExpression.class, match.sections().getFirst().patterns().getFirst());
        assertEquals(1, outer.entries().size());
        assertStringLiteral(outer.entries().getFirst().key(), "\"user\"");

        var inner = assertInstanceOf(DictionaryExpression.class, outer.entries().getFirst().value());
        assertEquals(1, inner.entries().size());
        assertStringLiteral(inner.entries().getFirst().key(), "\"name\"");
        assertEquals("n", assertInstanceOf(PatternBindingExpression.class, inner.entries().getFirst().value()).name());
    }

    @Test
    void trailingRestParsesOpenEndedWhileLeadingRestFailsAtParse() {
        var trailingUnit = parse("match_array_rest_trailing.gd", """
                func probe(x):
                    match x:
                        [1, ..]:
                            pass
                """);
        var trailingMatch = matchFromFunction(trailingUnit, "probe");
        var trailingArray = assertInstanceOf(ArrayExpression.class, trailingMatch.sections().getFirst().patterns().getFirst());
        assertTrue(trailingArray.openEnded());
        assertEquals(1, trailingArray.elements().size());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, trailingArray.elements().getFirst()).sourceText());

        var leadingSection = parseWithMisplacedRest("match_array_rest_leading.gd", """
                func probe(x):
                    match x:
                        [.., 1]:
                            pass
                """);
        var leadingArray = assertInstanceOf(ArrayExpression.class, leadingSection.patterns().getFirst());
        assertTrue(leadingArray.openEnded());
        assertEquals(1, leadingArray.elements().size());
        assertEquals("1", assertInstanceOf(LiteralExpression.class, leadingArray.elements().getFirst()).sourceText());
    }

    @Test
    void dictionaryTrailingRestParsesOpenEndedWhileLeadingRestFailsAtParse() {
        var unit = parse("match_dict_rest_trailing.gd", """
                func probe(x):
                    match x:
                        {"k": 1, ..}:
                            pass
                """);
        var match = matchFromFunction(unit, "probe");
        var dictionary = assertInstanceOf(DictionaryExpression.class, match.sections().getFirst().patterns().getFirst());
        assertTrue(dictionary.openEnded());
        assertEquals(1, dictionary.entries().size());
        assertStringLiteral(dictionary.entries().getFirst().key(), "\"k\"");
        assertEquals("1", assertInstanceOf(LiteralExpression.class, dictionary.entries().getFirst().value()).sourceText());

        var leadingSection = parseWithMisplacedRest("match_dict_rest_leading.gd", """
                func probe(x):
                    match x:
                        {.., "k": 1}:
                            pass
                """);
        var recovered = assertInstanceOf(DictionaryExpression.class, leadingSection.patterns().getFirst());
        assertTrue(recovered.openEnded());
        assertEquals(1, recovered.entries().size());
        assertStringLiteral(recovered.entries().getFirst().key(), "\"k\"");
        assertEquals("1", assertInstanceOf(LiteralExpression.class, recovered.entries().getFirst().value()).sourceText());
    }

    @Test
    void keyOnlyDictionaryPatternPreservesKeysWithoutValues() {
        var unit = parse("match_dict_key_only.gd", """
                func probe(x):
                    match x:
                        {"a", "b"}:
                            pass
                """);

        var match = matchFromFunction(unit, "probe");
        var dictionary = assertInstanceOf(DictionaryExpression.class, match.sections().getFirst().patterns().getFirst());
        assertFalse(dictionary.openEnded());
        assertEquals(2, dictionary.entries().size());

        var first = dictionary.entries().getFirst();
        assertStringLiteral(first.key(), "\"a\"");
        assertNull(first.value());

        var last = dictionary.entries().getLast();
        assertStringLiteral(last.key(), "\"b\"");
        assertNull(last.value());
    }

    private FrontendSourceUnit parse(String fileName, String source) {
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        assertTrue(
                diagnostics.snapshot().isEmpty(),
                () -> "unexpected diagnostics: " + diagnostics.snapshot().asList()
        );
        return unit;
    }

    private MatchSection parseWithMisplacedRest(String fileName, String source) {
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        assertFalse(unit.parseFailed());
        var errors = diagnostics.snapshot().asList();
        assertEquals(1, errors.size(), errors::toString);
        var error = errors.getFirst();
        assertEquals(DiagnosticCodes.MATCH_REST_NOT_LAST, error.category());
        assertEquals(FrontendDiagnosticSeverity.ERROR, error.severity());
        var range = error.range();
        assertNotNull(range);
        assertEquals("..", source.substring(range.startByte(), range.endByte()));

        var section = matchFromFunction(unit, "probe").sections().getFirst();
        assertEquals(1, unit.parseRecoveries().size());
        var recovery = unit.parseRecoveries().getFirst();
        assertEquals(error, recovery.toDiagnostic(error.sourcePath()));
        assertSame(section, recovery.owner());
        assertEquals("match.pattern", recovery.facet());
        return section;
    }

    private static MatchStatement matchFromFunction(FrontendSourceUnit unit, String functionName) {
        var function = assertInstanceOf(FunctionDeclaration.class, unit.ast().statements().getFirst());
        assertEquals(functionName, function.name());
        return assertInstanceOf(MatchStatement.class, function.body().statements().getFirst());
    }

    private static void assertLiteralKind(Expression expression, String kind) {
        assertEquals(kind, assertInstanceOf(LiteralExpression.class, expression).kind());
    }

    private static void assertStringLiteral(Expression expression, String sourceText) {
        var literal = assertInstanceOf(LiteralExpression.class, expression);
        assertEquals("string", literal.kind());
        assertEquals(sourceText, literal.sourceText());
    }
}

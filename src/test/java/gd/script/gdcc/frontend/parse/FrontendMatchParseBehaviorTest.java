package gd.script.gdcc.frontend.parse;

import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Freezes the gdparser 0.5.3 match-pattern AST contracts used by match lowering.
///
/// Scope is parser shape only: how match sections/patterns are represented, and which Godot
/// pattern forms gdparser cannot represent (`[.., 1]` leading rest, key-only dictionary entries).
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
        // R1 probe: gdparser 0.5.3 grammar only admits `..` in trailing position. `[1, ..]` maps to
        // openEnded=true; `[.., 1]` is a CST structural error instead of an open-ended array, so the
        // "position of `..` is not representable" deviation shows up as parse failure, not silent acceptance.
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

        assertParseLoweringError("match_array_rest_leading.gd", """
                func probe(x):
                    match x:
                        [.., 1]:
                            pass
                """);
    }

    @Test
    void dictionaryTrailingRestParsesOpenEndedWhileLeadingRestFailsAtParse() {
        // Symmetric R1 anchor for dictionaries: the grammar admits `..` only in trailing position.
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

        assertParseLoweringError("match_dict_rest_leading.gd", """
                func probe(x):
                    match x:
                        {.., "k": 1}:
                            pass
                """);
    }

    @Test
    void keyOnlyDictionaryEntryIsNotRepresentable() {
        // R8 probe: Godot accepts key-only entries `{"a", "b"}` (key existence check). The
        // gdparser 0.5.3 grammar admits the form as two `_primary_expression` dictionary
        // children, but the mapper only consumes `pair` / `pattern_open_ending` children: it
        // drops the keys with `parse.lowering` warnings and maps to a zero-entry dictionary, so
        // key-existence semantics are not representable. The form is therefore removed from the
        // match support surface (`{"k": _}` is the equivalent supported spelling); do not patch
        // or upgrade gdparser for it (architectural change, out of scope).
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "match_dict_key_only.gd"), """
                func probe(x):
                    match x:
                        {"a", "b"}:
                            pass
                """, diagnostics);

        var match = matchFromFunction(unit, "probe");
        var dictionary = assertInstanceOf(DictionaryExpression.class, match.sections().getFirst().patterns().getFirst());
        assertTrue(dictionary.entries().isEmpty());

        var warnings = diagnostics.snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("parse.lowering")
                        && diagnostic.severity() == FrontendDiagnosticSeverity.WARNING)
                .toList();
        assertEquals(2, warnings.size(), () -> "expected one warning per dropped key, got: " + diagnostics.snapshot().asList());
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

    private void assertParseLoweringError(String fileName, String source) {
        var diagnostics = new DiagnosticManager();
        parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        assertFalse(diagnostics.snapshot().isEmpty(), "expected parse diagnostics");
        assertTrue(diagnostics.snapshot().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("parse.lowering")
                        && diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
                        && diagnostic.range() != null
        ), () -> "expected parse.lowering ERROR, got: " + diagnostics.snapshot().asList());
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

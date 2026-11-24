package gd.script.gdcc.frontend.parse;

import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.ErrorExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.gdscript.DiagnosticCodes;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Freezes gdparser container-literal AST contracts required by
/// `frontend_container_literal_implementation.md`.
///
/// Scope is parser shape only: element counts, trailing comma vs `openEnded`,
/// Python/Lua dictionary styles, and parser diagnostic attribution. Semantic
/// typing and lowering stay out of this suite.
class FrontendContainerLiteralParseBehaviorTest {
    private final GdScriptParserService parserService = new GdScriptParserService();

    @Test
    void ordinaryArrayLiteralKeepsElementOrderWithoutOpenEnded() {
        var unit = parse("array_ordinary.gd", """
                func probe():
                    return [1, "two", null]
                """);

        var array = arrayFromReturn(unit, "probe");
        assertFalse(array.openEnded());
        assertEquals(3, array.elements().size());
        assertIntegerLiteral(array.elements().getFirst(), "1");
        assertStringLiteral(array.elements().get(1), "\"two\"");
        assertLiteralKind(array.elements().getLast(), "null");
    }

    @Test
    void emptyArrayLiteralIsValidAndClosed() {
        var unit = parse("array_empty.gd", """
                func probe():
                    return []
                """);

        var array = arrayFromReturn(unit, "probe");
        assertFalse(array.openEnded());
        assertTrue(array.elements().isEmpty());
    }

    @Test
    void trailingCommaOnArrayDoesNotSetOpenEnded() {
        // `[1, 2,]` → elements=2, openEnded=false.
        var unit = parse("array_trailing_comma.gd", """
                func probe():
                    return [1, 2,]
                """);

        var array = arrayFromReturn(unit, "probe");
        assertFalse(array.openEnded());
        assertEquals(2, array.elements().size());
        assertIntegerLiteral(array.elements().getFirst(), "1");
        assertIntegerLiteral(array.elements().getLast(), "2");
    }

    @Test
    void arrayValueRejectsPatternRest() {
        var unit = parseWithReturnError("array_open_ended.gd", """
                func probe():
                    return [1, 2, ..]
                """, DiagnosticCodes.SLOT_ARRAY_ELEMENT, 30, 31);

        var array = arrayFromReturn(unit, "probe");
        assertFalse(array.openEnded());
        assertEquals(3, array.elements().size());
        assertIntegerLiteral(array.elements().getFirst(), "1");
        assertIntegerLiteral(array.elements().get(1), "2");
        assertInstanceOf(ErrorExpression.class, array.elements().getLast());
    }

    @Test
    void nestedArrayLiteralPreservesInnerShape() {
        var unit = parse("array_nested.gd", """
                func probe():
                    return [[1], [2, 3]]
                """);

        var outer = arrayFromReturn(unit, "probe");
        assertFalse(outer.openEnded());
        assertEquals(2, outer.elements().size());

        var first = assertInstanceOf(ArrayExpression.class, outer.elements().getFirst());
        assertFalse(first.openEnded());
        assertEquals(1, first.elements().size());
        assertIntegerLiteral(first.elements().getFirst(), "1");

        var second = assertInstanceOf(ArrayExpression.class, outer.elements().getLast());
        assertFalse(second.openEnded());
        assertEquals(2, second.elements().size());
    }

    @Test
    void pythonStyleDictionaryKeepsExpressionKeys() {
        // `{x: 1}` stays expression-key semantics (identifier read, not StringName constant).
        var unit = parse("dict_python_style.gd", """
                func probe(x):
                    return {x: 1, "y": 2, &"z": 3}
                """);

        var dictionary = dictionaryFromReturn(unit, "probe");
        assertFalse(dictionary.openEnded());
        assertEquals(3, dictionary.entries().size());

        var first = dictionary.entries().getFirst();
        assertEquals("x", assertInstanceOf(IdentifierExpression.class, first.key()).name());
        assertIntegerLiteral(first.value(), "1");

        var second = dictionary.entries().get(1);
        assertStringLiteral(second.key(), "\"y\"");
        assertIntegerLiteral(second.value(), "2");

        var third = dictionary.entries().getLast();
        assertStringNameLiteral(third.key(), "&\"z\"");
        assertIntegerLiteral(third.value(), "3");
    }

    @Test
    void emptyDictionaryLiteralIsValidAndClosed() {
        var unit = parse("dict_empty.gd", """
                func probe():
                    return {}
                """);

        var dictionary = dictionaryFromReturn(unit, "probe");
        assertFalse(dictionary.openEnded());
        assertTrue(dictionary.entries().isEmpty());
    }

    @Test
    void trailingCommaOnDictionaryDoesNotSetOpenEnded() {
        // `{"x": 1,}` → entries=1, openEnded=false.
        var unit = parse("dict_trailing_comma.gd", """
                func probe():
                    return {"x": 1,}
                """);

        var dictionary = dictionaryFromReturn(unit, "probe");
        assertFalse(dictionary.openEnded());
        assertEquals(1, dictionary.entries().size());
        assertStringLiteral(dictionary.entries().getFirst().key(), "\"x\"");
        assertIntegerLiteral(dictionary.entries().getFirst().value(), "1");
    }

    @Test
    void dictionaryValueRejectsPatternRest() {
        var unit = parseWithReturnError("dict_open_ended.gd", """
                func probe():
                    return {"x": 1, ..}
                """, DiagnosticCodes.SLOT_DICTIONARY_KEY, 32, 33);

        var dictionary = dictionaryFromReturn(unit, "probe");
        assertFalse(dictionary.openEnded());
        assertEquals(1, dictionary.entries().size());
        var entry = dictionary.entries().getFirst();
        assertStringLiteral(entry.key(), "\"x\"");
        assertIntegerLiteral(entry.value(), "1");
    }

    @Test
    void nestedDictionaryLiteralPreservesInnerShape() {
        var unit = parse("dict_nested.gd", """
                func probe():
                    return {"outer": {"inner": 1}}
                """);

        var outer = dictionaryFromReturn(unit, "probe");
        assertFalse(outer.openEnded());
        assertEquals(1, outer.entries().size());
        assertStringLiteral(outer.entries().getFirst().key(), "\"outer\"");

        var inner = assertInstanceOf(DictionaryExpression.class, outer.entries().getFirst().value());
        assertFalse(inner.openEnded());
        assertEquals(1, inner.entries().size());
        assertStringLiteral(inner.entries().getFirst().key(), "\"inner\"");
        assertIntegerLiteral(inner.entries().getFirst().value(), "1");
    }

    @Test
    void luaStyleIdentifierKeyIsStringNameConstantNotVariableRead() {
        // `{x = 1}` key must not be ordinary IdentifierExpression.
        var unit = parse("dict_lua_identifier_key.gd", """
                func probe():
                    return {x = 1}
                """);

        var dictionary = dictionaryFromReturn(unit, "probe");
        assertFalse(dictionary.openEnded());
        assertEquals(1, dictionary.entries().size());

        var entry = dictionary.entries().getFirst();
        assertStringNameLiteral(entry.key(), "&\"x\"");
        assertIntegerLiteral(entry.value(), "1");
    }

    @Test
    void luaStyleStringKeyIsStringNameConstant() {
        // `{"name" = value}` is Lua-style StringName key, not a String key.
        var unit = parse("dict_lua_string_key.gd", """
                func probe():
                    return {"name" = 1}
                """);

        var dictionary = dictionaryFromReturn(unit, "probe");
        assertFalse(dictionary.openEnded());
        assertEquals(1, dictionary.entries().size());

        var entry = dictionary.entries().getFirst();
        assertStringNameLiteral(entry.key(), "&\"name\"");
        assertIntegerLiteral(entry.value(), "1");
    }

    @Test
    void mixedDictionaryStylesProduceSpecificParseError() {
        var unit = parseWithReturnError("dict_mixed_styles.gd", """
                func probe():
                    return {x: 1, y = 2}
                """, DiagnosticCodes.DICTIONARY_MIXED_STYLE, 34, 35);

        var dictionary = dictionaryFromReturn(unit, "probe");
        assertEquals(2, dictionary.entries().size());
        assertEquals("x", assertInstanceOf(IdentifierExpression.class, dictionary.entries().getFirst().key()).name());
        assertEquals("y", assertInstanceOf(IdentifierExpression.class, dictionary.entries().getLast().key()).name());
    }

    @Test
    void invalidLuaStyleNonStringKeyProducesSpecificParseError() {
        var unit = parseWithReturnError("dict_invalid_lua_key.gd", """
                func probe():
                    return {1 = "x"}
                """, DiagnosticCodes.DICTIONARY_INVALID_LUA_KEY, 28, 29);

        var dictionary = dictionaryFromReturn(unit, "probe");
        assertEquals(1, dictionary.entries().size());
        var entry = dictionary.entries().getFirst();
        assertIntegerLiteral(entry.key(), "1");
        assertStringLiteral(entry.value(), "\"x\"");
    }

    @Test
    void invalidDictionaryKeyDiagnosticPreservesRangeAndSiblingFunction() {
        var unit = parseWithReturnError("dict_parse_recovery.gd", """
                func probe():
                    return {1 = "x"}

                func after():
                    return 2
                """, DiagnosticCodes.DICTIONARY_INVALID_LUA_KEY, 28, 29);

        var recovery = unit.parseRecoveries().getFirst();
        assertTrue(recovery.message().startsWith("Expected identifier or string as Lua-style dictionary key"));
        var range = recovery.range();
        assertNotNull(range);
        assertEquals("=", unit.source().substring(range.startByte(), range.endByte()));

        assertEquals(2, unit.ast().statements().size());
        var after = assertInstanceOf(FunctionDeclaration.class, unit.ast().statements().getLast());
        assertEquals("after", after.name());
        var returnStatement = assertInstanceOf(ReturnStatement.class, after.body().statements().getFirst());
        assertIntegerLiteral(returnStatement.value(), "2");
    }

    @Test
    void arrayAndDictionaryLiteralsCanAppearInLocalInitializers() {
        var unit = parse("container_local_init.gd", """
                func probe():
                    var values = [1, 2,]
                    var config = {"x": 1,}
                    return values
                """);

        var function = assertInstanceOf(FunctionDeclaration.class, unit.ast().statements().getFirst());
        assertEquals("probe", function.name());

        var values = assertInstanceOf(
                ArrayExpression.class,
                assertInstanceOf(VariableDeclaration.class, function.body().statements().getFirst()).value()
        );
        assertFalse(values.openEnded());
        assertEquals(2, values.elements().size());

        var config = assertInstanceOf(
                DictionaryExpression.class,
                assertInstanceOf(VariableDeclaration.class, function.body().statements().get(1)).value()
        );
        assertFalse(config.openEnded());
        assertEquals(1, config.entries().size());
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

    private FrontendSourceUnit parseWithReturnError(
            String fileName,
            String source,
            String expectedCode,
            int expectedStartByte,
            int expectedEndByte
    ) {
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        assertFalse(unit.parseFailed());
        var errors = diagnostics.snapshot().asList();
        assertEquals(1, errors.size(), errors::toString);
        var error = errors.getFirst();
        assertEquals(expectedCode, error.category());
        assertEquals(FrontendDiagnosticSeverity.ERROR, error.severity());
        var expectedPath = "tmp/" + fileName;
        assertEquals(expectedPath, error.sourcePath());
        var range = error.range();
        assertNotNull(range);
        assertEquals(expectedStartByte, range.startByte());
        assertEquals(expectedEndByte, range.endByte());

        assertEquals(1, unit.parseRecoveries().size());
        var recovery = unit.parseRecoveries().getFirst();
        assertEquals(error, recovery.toDiagnostic(expectedPath));
        assertSame(returnFromFunction(unit, "probe"), recovery.owner());
        assertEquals("return.value", recovery.facet());
        return unit;
    }

    private static ArrayExpression arrayFromReturn(FrontendSourceUnit unit, String functionName) {
        return assertInstanceOf(ArrayExpression.class, returnFromFunction(unit, functionName).value());
    }

    private static DictionaryExpression dictionaryFromReturn(FrontendSourceUnit unit, String functionName) {
        return assertInstanceOf(DictionaryExpression.class, returnFromFunction(unit, functionName).value());
    }

    private static ReturnStatement returnFromFunction(FrontendSourceUnit unit, String functionName) {
        var function = assertInstanceOf(FunctionDeclaration.class, unit.ast().statements().getFirst());
        assertEquals(functionName, function.name());
        return assertInstanceOf(ReturnStatement.class, function.body().statements().getFirst());
    }

    private static void assertIntegerLiteral(Expression expression, String sourceText) {
        var literal = assertInstanceOf(LiteralExpression.class, expression);
        assertEquals("integer", literal.kind());
        assertEquals(sourceText, literal.sourceText());
    }

    private static void assertStringLiteral(Expression expression, String sourceText) {
        var literal = assertInstanceOf(LiteralExpression.class, expression);
        assertEquals("string", literal.kind());
        assertEquals(sourceText, literal.sourceText());
    }

    private static void assertStringNameLiteral(Expression expression, String sourceText) {
        var literal = assertInstanceOf(LiteralExpression.class, expression);
        assertEquals("string_name", literal.kind());
        assertEquals(sourceText, literal.sourceText());
    }

    private static void assertLiteralKind(Expression expression, String kind) {
        var literal = assertInstanceOf(LiteralExpression.class, expression);
        assertEquals(kind, literal.kind());
    }
}

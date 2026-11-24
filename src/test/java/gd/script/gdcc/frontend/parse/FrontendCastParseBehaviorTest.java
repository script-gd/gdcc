package gd.script.gdcc.frontend.parse;

import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.RecoveredInfixExpression;
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

/// Freezes gdparser {@code CastExpression} AST shape for explicit cast support.
///
/// Precedence, associativity, and RHS {@code TypeRef} contracts come from Godot (4.5.1/4.7.1
/// identical for {@code as}) and the already-shipped gdparser dependency; this suite only
/// characterization-tests the consumed AST.
class FrontendCastParseBehaviorTest {
    private final GdScriptParserService parserService = new GdScriptParserService();

    @Test
    void unparenthesizedPlusBindsTighterThanCast() {
        var unit = parse("cast_plus_precedence.gd", """
                func probe(a, b):
                    return a + b as float
                """);

        var cast = castFromReturn(unit, "probe");
        assertEquals("float", cast.targetType().sourceText());

        var sum = assertInstanceOf(BinaryExpression.class, cast.value());
        assertEquals("+", sum.operator());
        assertEquals("a", assertInstanceOf(IdentifierExpression.class, sum.left()).name());
        assertEquals("b", assertInstanceOf(IdentifierExpression.class, sum.right()).name());
    }

    @Test
    void parenthesizedSumCanBeCastOperand() {
        var unit = parse("cast_parenthesized_sum.gd", """
                func probe(a, b):
                    return (a + b) as float
                """);

        var cast = castFromReturn(unit, "probe");
        assertEquals("float", cast.targetType().sourceText());

        var sum = assertInstanceOf(BinaryExpression.class, cast.value());
        assertEquals("+", sum.operator());
        assertEquals("a", assertInstanceOf(IdentifierExpression.class, sum.left()).name());
        assertEquals("b", assertInstanceOf(IdentifierExpression.class, sum.right()).name());
    }

    @Test
    void chainedCastIsLeftAssociative() {
        var unit = parse("cast_left_assoc.gd", """
                func probe(x):
                    return x as int as float
                """);

        var outer = castFromReturn(unit, "probe");
        assertEquals("float", outer.targetType().sourceText());

        var inner = assertInstanceOf(CastExpression.class, outer.value());
        assertEquals("int", inner.targetType().sourceText());
        assertEquals("x", assertInstanceOf(IdentifierExpression.class, inner.value()).name());
    }

    @Test
    void targetTypeRefKeepsSourceFacingText() {
        var unit = parse("cast_target_text.gd", """
                func probe(value):
                    var a = value as Node
                    var b = value as Array[int]
                    return a
                """);

        var function = assertInstanceOf(FunctionDeclaration.class, unit.ast().statements().getFirst());
        assertEquals("probe", function.name());

        var nodeCast = assertInstanceOf(
                CastExpression.class,
                assertInstanceOf(VariableDeclaration.class, function.body().statements().getFirst()).value()
        );
        assertEquals("Node", nodeCast.targetType().sourceText());
        assertEquals("value", assertInstanceOf(IdentifierExpression.class, nodeCast.value()).name());

        var arrayCast = assertInstanceOf(
                CastExpression.class,
                assertInstanceOf(VariableDeclaration.class, function.body().statements().get(1)).value()
        );
        assertEquals("Array[int]", arrayCast.targetType().sourceText());
    }

    @Test
    void simpleCastParsesAsCastExpressionWithIdentifierOperand() {
        var unit = parse("cast_simple.gd", """
                func probe(value):
                    return value as String
                """);

        var cast = castFromReturn(unit, "probe");
        assertEquals("String", cast.targetType().sourceText());
        assertEquals("value", assertInstanceOf(IdentifierExpression.class, cast.value()).name());
    }

    @Test
    void missingCastTypeSpecifierKeepsOperandInRecoveredInfix() {
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "cast_missing_type.gd"), """
                func probe(value):
                    return value as
                """, diagnostics);

        assertFalse(unit.parseFailed());
        var returnStatement = returnFromFunction(unit, "probe");
        var recovered = assertInstanceOf(RecoveredInfixExpression.class, returnStatement.value());
        assertEquals("as", recovered.operator());
        assertEquals("value", assertInstanceOf(IdentifierExpression.class, recovered.operand()).name());
        assertEquals("cast.type", recovered.problem().nodeType());

        var errors = diagnostics.snapshot().asList();
        assertEquals(1, errors.size(), errors::toString);
        var error = errors.getFirst();
        assertEquals(DiagnosticCodes.MISSING_TYPE_CAST, error.category());
        assertEquals(FrontendDiagnosticSeverity.ERROR, error.severity());
        var range = error.range();
        assertNotNull(range);
        assertEquals("as", unit.source().substring(range.startByte(), range.endByte()));
        assertEquals(range.endByte(), recovered.problem().range().startByte());
        assertEquals(range.endByte(), recovered.problem().range().endByte());

        assertEquals(1, unit.parseRecoveries().size());
        var recovery = unit.parseRecoveries().getFirst();
        assertEquals(error, recovery.toDiagnostic(error.sourcePath()));
        assertSame(returnStatement, recovery.owner());
        assertEquals("return.value", recovery.facet());
    }

    private FrontendSourceUnit parse(String fileName, String source) {
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        assertTrue(diagnostics.snapshot().isEmpty(), () -> "unexpected diagnostics: " + diagnostics.snapshot().asList());
        return unit;
    }

    private static CastExpression castFromReturn(FrontendSourceUnit unit, String functionName) {
        return assertInstanceOf(CastExpression.class, returnFromFunction(unit, functionName).value());
    }

    private static ReturnStatement returnFromFunction(FrontendSourceUnit unit, String functionName) {
        var function = assertInstanceOf(FunctionDeclaration.class, unit.ast().statements().getFirst());
        assertEquals(functionName, function.name());
        return assertInstanceOf(ReturnStatement.class, function.body().statements().getFirst());
    }
}

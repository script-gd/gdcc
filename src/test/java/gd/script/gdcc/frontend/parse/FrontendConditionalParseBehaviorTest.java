package gd.script.gdcc.frontend.parse;

import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Freezes gdparser {@code ConditionalExpression} AST shape for ternary support.
///
/// Associativity and precedence come from tree-sitter-gdscript
/// (`prec.right(PREC.conditional = -1)`) and the shipped gdparser mapper, which
/// passes `left`/`condition`/`right` through without swapping. This suite only
/// characterization-tests the consumed AST; sema and lowering stay out.
class FrontendConditionalParseBehaviorTest {
    private final GdScriptParserService parserService = new GdScriptParserService();

    @Test
    void mapsLeftConditionRightWithoutFieldSwap() {
        // Record component order is condition/left/right, but source order is
        // `left if condition else right`. Mapper must not swap those fields.
        var unit = parse("conditional_fields.gd", """
                func probe(yes, flag, no):
                    return yes if flag else no
                """);

        var conditional = conditionalFromReturn(unit, "probe");
        assertIdentifier(conditional.left(), "yes");
        assertIdentifier(conditional.condition(), "flag");
        assertIdentifier(conditional.right(), "no");
        assertEquals(List.of(conditional.condition(), conditional.left(), conditional.right()), conditional.getChildren());
    }

    @Test
    void nestedTernaryIsRightAssociative() {
        var unit = parse("conditional_right_assoc.gd", """
                func probe(a, c1, b, c2, d):
                    return a if c1 else b if c2 else d
                """);

        var outer = conditionalFromReturn(unit, "probe");
        assertIdentifier(outer.left(), "a");
        assertIdentifier(outer.condition(), "c1");

        var inner = assertInstanceOf(ConditionalExpression.class, outer.right());
        assertIdentifier(inner.left(), "b");
        assertIdentifier(inner.condition(), "c2");
        assertIdentifier(inner.right(), "d");
    }

    @Test
    void parenthesizedTernaryForcesLeftAssociativeShape() {
        // Parentheses are unwrapped by the mapper, so the inner ternary is the
        // outer `left` operand rather than a ParenthesizedExpression wrapper.
        var unit = parse("conditional_parenthesized_left.gd", """
                func probe(a, c1, b, c2, d):
                    return (a if c1 else b) if c2 else d
                """);

        var outer = conditionalFromReturn(unit, "probe");
        assertIdentifier(outer.condition(), "c2");
        assertIdentifier(outer.right(), "d");

        var inner = assertInstanceOf(ConditionalExpression.class, outer.left());
        assertIdentifier(inner.left(), "a");
        assertIdentifier(inner.condition(), "c1");
        assertIdentifier(inner.right(), "b");
    }

    @Test
    void explicitRightNestingMatchesUnparenthesizedAssociativity() {
        var parenthesized = conditionalFromReturn(parse("conditional_parenthesized_right.gd", """
                func probe(a, c1, b, c2, d):
                    return a if c1 else (b if c2 else d)
                """), "probe");
        var unparenthesized = conditionalFromReturn(parse("conditional_right_assoc.gd", """
                func probe(a, c1, b, c2, d):
                    return a if c1 else b if c2 else d
                """), "probe");
        // Parentheses change source ranges; identifier-level shape must still match.
        assertSameIdentifierShape(parenthesized, unparenthesized);
    }

    @Test
    void additiveBindsTighterThanConditional() {
        var unit = parse("conditional_plus_precedence.gd", """
                func probe(a, b, c, d):
                    return a + b if c else d
                """);

        var conditional = conditionalFromReturn(unit, "probe");
        var sum = assertInstanceOf(BinaryExpression.class, conditional.left());
        assertEquals("+", sum.operator());
        assertIdentifier(sum.left(), "a");
        assertIdentifier(sum.right(), "b");
        assertIdentifier(conditional.condition(), "c");
        assertIdentifier(conditional.right(), "d");
    }

    @Test
    void additiveInFalseArmBindsTighterThanConditional() {
        var unit = parse("conditional_plus_in_right.gd", """
                func probe(a, c, d, e):
                    return a if c else d + e
                """);

        var conditional = conditionalFromReturn(unit, "probe");
        assertIdentifier(conditional.left(), "a");
        assertIdentifier(conditional.condition(), "c");
        var sum = assertInstanceOf(BinaryExpression.class, conditional.right());
        assertEquals("+", sum.operator());
        assertIdentifier(sum.left(), "d");
        assertIdentifier(sum.right(), "e");
    }

    @Test
    void booleanOrBindsTighterThanConditional() {
        var unit = parse("conditional_or_precedence.gd", """
                func probe(a, b, c, d):
                    return a or b if c else d
                """);

        var conditional = conditionalFromReturn(unit, "probe");
        var orExpr = assertInstanceOf(BinaryExpression.class, conditional.left());
        assertEquals("or", orExpr.operator());
        assertIdentifier(orExpr.left(), "a");
        assertIdentifier(orExpr.right(), "b");
        assertIdentifier(conditional.condition(), "c");
        assertIdentifier(conditional.right(), "d");
    }

    @Test
    void unaryNotBindsTighterThanConditional() {
        var unit = parse("conditional_not_precedence.gd", """
                func probe(a, c, d):
                    return not a if c else d
                """);

        var conditional = conditionalFromReturn(unit, "probe");
        var notExpr = assertInstanceOf(UnaryExpression.class, conditional.left());
        assertEquals("not", notExpr.operator());
        assertIdentifier(notExpr.operand(), "a");
        assertIdentifier(conditional.condition(), "c");
        assertIdentifier(conditional.right(), "d");
    }

    @Test
    void castBindsTighterThanConditional() {
        var unit = parse("conditional_cast_precedence.gd", """
                func probe(a, c, d):
                    return a as int if c else d
                """);

        var conditional = conditionalFromReturn(unit, "probe");
        var cast = assertInstanceOf(CastExpression.class, conditional.left());
        assertEquals("int", cast.targetType().sourceText());
        assertIdentifier(cast.value(), "a");
        assertIdentifier(conditional.condition(), "c");
        assertIdentifier(conditional.right(), "d");
    }

    @Test
    void orInConditionSlotIsNotAbsorbedByTernary() {
        var unit = parse("conditional_or_in_condition.gd", """
                func probe(a, b, c, d):
                    return a if b or c else d
                """);

        var conditional = conditionalFromReturn(unit, "probe");
        assertIdentifier(conditional.left(), "a");
        var orExpr = assertInstanceOf(BinaryExpression.class, conditional.condition());
        assertEquals("or", orExpr.operator());
        assertIdentifier(orExpr.left(), "b");
        assertIdentifier(orExpr.right(), "c");
        assertIdentifier(conditional.right(), "d");
    }

    @Test
    void ifStatementConditionCanBeTernary() {
        var unit = parse("conditional_if_condition.gd", """
                func probe(a, c, b):
                    if a if c else b:
                        return 1
                """);

        var function = assertInstanceOf(FunctionDeclaration.class, unit.ast().statements().getFirst());
        assertEquals("probe", function.name());
        var ifStatement = assertInstanceOf(IfStatement.class, function.body().statements().getFirst());
        var conditional = assertInstanceOf(ConditionalExpression.class, ifStatement.condition());
        assertIdentifier(conditional.left(), "a");
        assertIdentifier(conditional.condition(), "c");
        assertIdentifier(conditional.right(), "b");
    }

    @Test
    void siblingReturnAfterTernaryStillParses() {
        var unit = parse("conditional_sibling_return.gd", """
                func probe(flag):
                    return 1 if flag else 2
                    return 3
                """);

        var function = assertInstanceOf(FunctionDeclaration.class, unit.ast().statements().getFirst());
        assertEquals("probe", function.name());
        assertEquals(2, function.body().statements().size());

        var ternaryReturn = assertInstanceOf(ReturnStatement.class, function.body().statements().getFirst());
        var conditional = assertInstanceOf(ConditionalExpression.class, ternaryReturn.value());
        assertIntegerLiteral(conditional.left(), "1");
        assertIdentifier(conditional.condition(), "flag");
        assertIntegerLiteral(conditional.right(), "2");

        var siblingReturn = assertInstanceOf(ReturnStatement.class, function.body().statements().getLast());
        assertIntegerLiteral(siblingReturn.value(), "3");
    }

    @Test
    void missingElseProducesParseLoweringError() {
        assertParseLoweringError("conditional_missing_else.gd", """
                func probe(flag):
                    return 1 if flag
                """);
    }

    @Test
    void missingTrueArmProducesParseLoweringError() {
        assertParseLoweringError("conditional_missing_left.gd", """
                func probe(flag):
                    return if flag else 1
                """);
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

    private static ConditionalExpression conditionalFromReturn(FrontendSourceUnit unit, String functionName) {
        return assertInstanceOf(ConditionalExpression.class, expressionFromReturn(unit, functionName));
    }

    private static Expression expressionFromReturn(FrontendSourceUnit unit, String functionName) {
        var function = assertInstanceOf(FunctionDeclaration.class, unit.ast().statements().getFirst());
        assertEquals(functionName, function.name());
        var returnStatement = assertInstanceOf(ReturnStatement.class, function.body().statements().getFirst());
        return returnStatement.value();
    }

    private static void assertIdentifier(Expression expression, String name) {
        assertEquals(name, assertInstanceOf(IdentifierExpression.class, expression).name());
    }

    private static void assertIntegerLiteral(Expression expression, String sourceText) {
        var literal = assertInstanceOf(LiteralExpression.class, expression);
        assertEquals("integer", literal.kind());
        assertEquals(sourceText, literal.sourceText());
    }

    /// Compares identifier names through nested ternaries, ignoring source ranges.
    private static void assertSameIdentifierShape(Expression expected, Expression actual) {
        switch (expected) {
            case ConditionalExpression expectedConditional when actual instanceof ConditionalExpression actualConditional -> {
                assertSameIdentifierShape(expectedConditional.left(), actualConditional.left());
                assertSameIdentifierShape(expectedConditional.condition(), actualConditional.condition());
                assertSameIdentifierShape(expectedConditional.right(), actualConditional.right());
            }
            case IdentifierExpression expectedIdentifier when actual instanceof IdentifierExpression actualIdentifier ->
                    assertEquals(expectedIdentifier.name(), actualIdentifier.name());
            default -> fail("shape mismatch: " + expected.getClass().getSimpleName()
                    + " vs " + actual.getClass().getSimpleName());
        }
    }
}

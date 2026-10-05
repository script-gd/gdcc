package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.ErrorExpression;
import dev.superice.gdparser.frontend.ast.ErrorStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.UnknownExpression;
import dev.superice.gdparser.frontend.ast.UnknownStatement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.cst.CstIssueKind;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Unit tests for the error-subtree promotion rules of `FrontendErrorSubtreeAnnotator`
/// (LSP foundation plan §2.2.2), driven with hand-built ASTs because some shapes (notably a
/// broken parameter default island) are not producible from real gdparser 0.6.0 source parses.
class FrontendErrorSubtreeAnnotatorTest {
    private static final Range RANGE = new Range(0, 0, new Point(0, 0), new Point(0, 0));

    @Test
    void errorStatementMarksItselfOnly() {
        var error = errorStatement();
        var sibling = new PassStatement(RANGE);
        var analysisData = annotate(functionWithBody(new Block(List.of(error, sibling), RANGE)));

        assertTrue(analysisData.skippedSubtreeRoots().containsKey(error));
        assertFalse(analysisData.skippedSubtreeRoots().containsKey(sibling));
        assertEquals(1, analysisData.skippedSubtreeRoots().size());
    }

    @Test
    void unknownStatementMarksItselfOnly() {
        var unknown = new UnknownStatement("mystery_node", "???", RANGE);
        var sibling = new PassStatement(RANGE);
        var analysisData = annotate(functionWithBody(new Block(List.of(unknown, sibling), RANGE)));

        assertTrue(analysisData.skippedSubtreeRoots().containsKey(unknown));
        assertFalse(analysisData.skippedSubtreeRoots().containsKey(sibling));
        assertEquals(1, analysisData.skippedSubtreeRoots().size());
    }

    @Test
    void errorExpressionInVariableValuePromotesToDeclarationStatement() {
        var damaged = new VariableDeclaration(
                DeclarationKind.VAR, "x", null, errorExpression(), false, null, RANGE);
        var analysisData = annotate(functionWithBody(new Block(List.of(damaged), RANGE)));

        assertTrue(analysisData.skippedSubtreeRoots().containsKey(damaged));
        assertEquals(1, analysisData.skippedSubtreeRoots().size());
    }

    @Test
    void unknownExpressionPromotesToEnclosingIfStatement() {
        var ifStatement = new IfStatement(
                new UnknownExpression("mystery_expr", "???", RANGE),
                new Block(List.of(new PassStatement(RANGE)), RANGE),
                List.of(),
                null,
                RANGE
        );
        var analysisData = annotate(functionWithBody(new Block(List.of(ifStatement), RANGE)));

        assertTrue(analysisData.skippedSubtreeRoots().containsKey(ifStatement));
        assertEquals(1, analysisData.skippedSubtreeRoots().size());
    }

    @Test
    void nestedBlockKeepsInnermostStatementAsRoot() {
        var damaged = new VariableDeclaration(
                DeclarationKind.VAR, "x", null, errorExpression(), false, null, RANGE);
        var innerBlock = new Block(List.of(damaged), RANGE);
        var outerBlock = new Block(List.of(innerBlock), RANGE);
        var analysisData = annotate(functionWithBody(outerBlock));

        assertTrue(analysisData.skippedSubtreeRoots().containsKey(damaged));
        assertFalse(analysisData.skippedSubtreeRoots().containsKey(innerBlock));
        assertFalse(analysisData.skippedSubtreeRoots().containsKey(outerBlock));
        assertEquals(1, analysisData.skippedSubtreeRoots().size());
    }

    @Test
    void parameterDefaultErrorMarksDefaultExpressionInsteadOfCallable() {
        var defaultValue = errorExpression();
        var parameter = new Parameter("x", null, defaultValue, false, RANGE);
        var bodyStatement = new PassStatement(RANGE);
        var function = function(List.of(parameter), new Block(List.of(bodyStatement), RANGE));
        var analysisData = annotate(function);

        // The island rule (plan §2.2.3): only the default expression root is marked. Marking the
        // function instead would strip its body scope and drag the whole run into structural
        // failure.
        assertTrue(analysisData.skippedSubtreeRoots().containsKey(defaultValue));
        assertFalse(analysisData.skippedSubtreeRoots().containsKey(function));
        assertFalse(analysisData.skippedSubtreeRoots().containsKey(parameter));
        assertFalse(analysisData.skippedSubtreeRoots().containsKey(bodyStatement));
        assertEquals(1, analysisData.skippedSubtreeRoots().size());
    }

    @Test
    void skeletonMarkedRootIsNotDescendedInto() {
        var ifStatement = new IfStatement(
                errorExpression(),
                new Block(List.of(new PassStatement(RANGE)), RANGE),
                List.of(),
                null,
                RANGE
        );
        var analysisData = FrontendAnalysisData.bootstrap();
        analysisData.skippedSubtreeRoots().put(ifStatement, Boolean.TRUE);
        annotate(functionWithBody(new Block(List.of(ifStatement), RANGE)), analysisData);

        // The annotator must not add finer-grained marks below a root the skeleton already owns.
        assertEquals(1, analysisData.skippedSubtreeRoots().size());
        assertTrue(analysisData.skippedSubtreeRoots().containsKey(ifStatement));
    }

    @Test
    void parseFailedUnitContributesNoMarks() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var failedUnit = new FrontendSourceUnit(
                Path.of("tmp", "broken.gd"),
                "class_name Broken\n",
                new SourceFile(List.of(), RANGE),
                true
        );
        FrontendErrorSubtreeAnnotator.annotate(
                new FrontendModule("test_module", List.of(failedUnit)),
                analysisData
        );
        assertTrue(analysisData.skippedSubtreeRoots().isEmpty());
    }

    private static FrontendAnalysisData annotate(FunctionDeclaration function) {
        var analysisData = FrontendAnalysisData.bootstrap();
        annotate(function, analysisData);
        return analysisData;
    }

    private static void annotate(FunctionDeclaration function, FrontendAnalysisData analysisData) {
        var unit = new FrontendSourceUnit(
                Path.of("tmp", "annotator.gd"),
                "",
                new SourceFile(List.of(function), RANGE)
        );
        FrontendErrorSubtreeAnnotator.annotate(new FrontendModule("test_module", List.of(unit)), analysisData);
    }

    private static FunctionDeclaration functionWithBody(Block body) {
        return function(List.of(), body);
    }

    private static FunctionDeclaration function(List<Parameter> parameters, Block body) {
        return new FunctionDeclaration("f", parameters, null, false, body, RANGE);
    }

    private static ErrorStatement errorStatement() {
        return new ErrorStatement(CstIssueKind.ERROR, "ERROR", "???", RANGE);
    }

    private static ErrorExpression errorExpression() {
        return new ErrorExpression(CstIssueKind.ERROR, "ERROR", "???", RANGE);
    }
}

package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.AnnotationStatement;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.DictEntry;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.EnumDeclaration;
import dev.superice.gdparser.frontend.ast.ErrorExpression;
import dev.superice.gdparser.frontend.ast.ErrorStatement;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SignalStatement;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.UnknownExpression;
import dev.superice.gdparser.frontend.ast.UnknownStatement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import dev.superice.gdparser.frontend.cst.CstIssueKind;
import dev.superice.gdparser.frontend.gdscript.DiagnosticCodes;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendParseRecovery;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks recovery boundaries for both parser attribution and malformed AST shapes.
class FrontendErrorSubtreeAnalyzerTest {
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
        FrontendErrorSubtreeAnalyzer.annotate(
                new FrontendModule("test_module", List.of(failedUnit)),
                analysisData
        );
        assertTrue(analysisData.skippedSubtreeRoots().isEmpty());
    }

    @Test
    void eachRegisteredFacetPrunesOnlyItsExpectedBoundary() {
        var diagnostics = new DiagnosticManager();
        var unit = parse("""
                @tool
                class Inner:
                    pass
                signal changed(value: int)
                enum State { READY = 1 }
                const LIMIT: int = 1
                func f(value: Array[int] = [1]) -> int:
                    assert(true, "msg")
                    for i in [0]: pass
                    while false: pass
                    if true: pass
                    elif false: pass
                    match 1:
                        0 when true: pass
                        {"a": var matched}: pass
                    var local: int = 1
                    var dictionary = {"a": 1}
                    print(value)
                    return value as int
                """, diagnostics);
        assertTrue(diagnostics.isEmpty(), () -> diagnostics.snapshot().toString());
        var top = unit.ast().statements();
        var annotation = assertInstanceOf(AnnotationStatement.class, top.getFirst());
        var inner = assertInstanceOf(ClassDeclaration.class, top.get(1));
        var signal = assertInstanceOf(SignalStatement.class, top.get(2));
        var enumeration = assertInstanceOf(EnumDeclaration.class, top.get(3));
        var constant = assertInstanceOf(VariableDeclaration.class, top.get(4));
        var function = assertInstanceOf(FunctionDeclaration.class, top.getLast());
        var parameter = function.parameters().getFirst();
        var statements = function.body().statements();
        var assertion = assertInstanceOf(AssertStatement.class, statements.getFirst());
        var forLoop = assertInstanceOf(ForStatement.class, statements.get(1));
        var whileLoop = assertInstanceOf(WhileStatement.class, statements.get(2));
        var ifStatement = assertInstanceOf(IfStatement.class, statements.get(3));
        var elif = ifStatement.elifClauses().getFirst();
        var match = assertInstanceOf(MatchStatement.class, statements.get(4));
        var section = match.sections().getFirst();
        var patternSection = match.sections().getLast();
        var pattern = assertInstanceOf(DictionaryExpression.class, patternSection.patterns().getFirst());
        var local = assertInstanceOf(VariableDeclaration.class, statements.get(5));
        var dictionary = assertInstanceOf(VariableDeclaration.class, statements.get(6));
        var entry = assertInstanceOf(DictEntry.class, dictionary.value().getChildren().getFirst());
        var expression = statements.get(7);
        var returned = assertInstanceOf(ReturnStatement.class, statements.getLast());
        var cast = assertInstanceOf(CastExpression.class, returned.value());
        var cases = List.of(
                new FacetCase("annotation.arguments", annotation, annotation),
                new FacetCase("annotation.target", annotation, annotation),
                new FacetCase("assert.arguments", assertion, assertion),
                new FacetCase("assert.condition", assertion, assertion),
                new FacetCase("assert.message", assertion, assertion),
                new FacetCase("class.body", inner, null),
                new FacetCase("class.extends", inner, null),
                new FacetCase("constant.type", constant, constant),
                new FacetCase("constant.value", constant, constant),
                new FacetCase("declaration.name", local, local),
                new FacetCase("dictionary.entry", entry, dictionary),
                new FacetCase("elif.condition", elif, elif),
                new FacetCase("enum.body", enumeration, enumeration),
                new FacetCase("enum.value", enumeration.members().getFirst(), enumeration),
                new FacetCase("expression", expression, expression),
                new FacetCase("for.iterable", forLoop, forLoop),
                new FacetCase("for.iterator", forLoop, forLoop),
                new FacetCase("function.name", function, null),
                new FacetCase("function.return_type", function, null),
                new FacetCase("function.signature", function, null),
                new FacetCase("if.condition", ifStatement, ifStatement),
                new FacetCase("match.dictionary_pattern", pattern, patternSection),
                new FacetCase("match.guard", section, section),
                new FacetCase("match.pattern", section, section),
                new FacetCase("match.section", section, section),
                new FacetCase("match.value", match, match),
                new FacetCase("parameter.default", parameter, parameter.defaultValue()),
                new FacetCase("parameter.type", parameter, null),
                new FacetCase("return.value", returned, returned),
                new FacetCase("signal.parameter", signal, null),
                new FacetCase("statement.control", returned, null),
                new FacetCase("statement.trailing", expression, null),
                new FacetCase("suite.body", function, null),
                new FacetCase("type.argument", cast.targetType(), returned),
                new FacetCase("variable.initializer", local, local),
                new FacetCase("variable.property", local, local),
                new FacetCase("variable.type", local, local),
                new FacetCase("while.condition", whileLoop, whileLoop)
        );
        assertEquals(38, new HashSet<>(cases.stream().map(FacetCase::facet).toList()).size());
        for (var testCase : cases) {
            assertTrue(DiagnosticCodes.isValidOwnerForFacet(testCase.facet(), testCase.owner().getClass()), testCase.facet());
            assertRecoveryBoundary(unit, testCase, DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN, FrontendDiagnosticSeverity.ERROR);
        }
        assertRecoveryBoundary(unit, new FacetCase("type.argument", parameter.type(), null),
                DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN, FrontendDiagnosticSeverity.ERROR);
        assertRecoveryBoundary(unit, new FacetCase("type.argument", function.returnType(), null),
                DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN, FrontendDiagnosticSeverity.ERROR);
        assertRecoveryBoundary(unit, new FacetCase("variable.initializer", local, null),
                DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN, FrontendDiagnosticSeverity.WARNING);
    }

    @Test
    void nestedCastKeepsOneParserErrorAndPublishesHealthySiblingInBothManagers() throws Exception {
        var parserDiagnostics = new DiagnosticManager();
        var unit = parse("""
                func f(value: Variant):
                    var nested = value as Array[Array[int]]
                    var after = 1
                """, parserDiagnostics);
        var function = assertInstanceOf(FunctionDeclaration.class, unit.ast().statements().getFirst());
        var nested = assertInstanceOf(VariableDeclaration.class, function.body().statements().getFirst());
        var cast = assertInstanceOf(CastExpression.class, nested.value());
        var after = assertInstanceOf(VariableDeclaration.class, function.body().statements().getLast());
        var expected = parserDiagnostics.snapshot().asList();
        assertEquals(1, expected.size());
        assertEquals(DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN, expected.getFirst().category());
        assertSame(nested, unit.parseRecoveries().getFirst().owner());
        var api = ExtensionApiLoader.loadDefault();
        for (var active : List.of(parserDiagnostics, new DiagnosticManager())) {
            var data = new FrontendSemanticAnalyzer().analyzeForCompile(
                    FrontendModule.singleUnit("test_module", unit), new ClassRegistry(api), active);
            assertEquals(expected, active.snapshot().asList());
            assertTrue(data.skippedSubtreeRoots().containsKey(nested));
            assertFalse(data.scopesByAst().containsKey(nested));
            assertFalse(data.expressionTypes().containsKey(cast));
            assertTrue(data.scopesByAst().containsKey(after));
            assertTrue(data.expressionTypes().containsKey(after.value()));
        }
    }

    @Test
    void realBranchDamageAndMissingColonDoNotEraseHealthySiblingBranches() {
        var diagnostics = new DiagnosticManager();
        var unit = parse("""
                func f(x: bool):
                    if x
                        pass
                    elif :
                        pass
                    elif x:
                        pass
                    match 1:
                        0 when :
                            pass
                        1:
                            pass
                    match 2:
                    pass
                """, diagnostics);
        var body = assertInstanceOf(FunctionDeclaration.class, unit.ast().statements().getFirst()).body();
        var conditional = assertInstanceOf(IfStatement.class, body.statements().getFirst());
        var match = assertInstanceOf(MatchStatement.class, body.statements().get(1));
        var missingBody = assertInstanceOf(MatchStatement.class, body.statements().get(2));
        assertTrue(missingBody.sections().isEmpty());
        var marked = annotateParsedUnit(unit).skippedSubtreeRoots();
        assertEquals(3, marked.size());
        assertTrue(marked.containsKey(conditional.elifClauses().getFirst()));
        assertTrue(marked.containsKey(match.sections().getFirst()));
        assertTrue(marked.containsKey(missingBody));
        assertFalse(marked.containsKey(conditional.elifClauses().getLast()));
        assertFalse(marked.containsKey(match.sections().getLast()));
    }

    @Test
    void memberAndSuperRecoveryCodesDoNotHideReceiverStatements() {
        var diagnostics = new DiagnosticManager();
        var unit = parse("""
                func f(v: Vector2):
                    var member = v.
                    var named = super.
                    var ordinary = super.payload
                    return super
                """, diagnostics);
        assertEquals(List.of(DiagnosticCodes.ATTRIBUTE_MISSING_NAME, DiagnosticCodes.SUPER_MISSING_NAME,
                        DiagnosticCodes.SUPER_MISSING_CALL, DiagnosticCodes.SUPER_MISSING_ACCESS),
                unit.parseRecoveries().stream().map(FrontendParseRecovery::code).toList());
        assertTrue(annotateParsedUnit(unit).skippedSubtreeRoots().isEmpty());
    }

    @Test
    void bareLambdaAndProjectedAnnotationAreMarkedByShapeWithoutParserRecovery() {
        var lambda = new LambdaExpression(null, List.of(), null, new Block(List.of(new PassStatement(RANGE)), RANGE), RANGE);
        var bare = new ExpressionStatement(lambda, RANGE);
        var projected = new AnnotationStatement("onready", List.of(), RANGE);
        var property = new VariableDeclaration(DeclarationKind.VAR, "value", null, null, false, "var", RANGE,
                List.of(projected));
        var marked = annotate(functionWithBody(new Block(List.of(bare, property), RANGE))).skippedSubtreeRoots();
        assertEquals(3, marked.size());
        assertTrue(marked.containsKey(bare));
        assertTrue(marked.containsKey(lambda));
        assertTrue(marked.containsKey(projected));
    }

    private static void assertRecoveryBoundary(
            FrontendSourceUnit unit, FacetCase testCase, String code, FrontendDiagnosticSeverity severity
    ) {
        var owner = testCase.owner();
        var data = FrontendAnalysisData.bootstrap();
        data.parseRecoveriesByOwner().put(owner, List.of(new FrontendParseRecovery(
                code, severity, "fixture recovery", FrontendRange.fromAstRange(owner.range()), owner, testCase.facet()
        )));
        FrontendErrorSubtreeAnalyzer.annotate(FrontendModule.singleUnit("test_module", unit), data);
        var marked = data.skippedSubtreeRoots();
        if (testCase.expectedRoot() == null) {
            assertTrue(marked.isEmpty(), testCase.facet());
        } else {
            assertEquals(1, marked.size(), testCase.facet());
            assertTrue(marked.containsKey(testCase.expectedRoot()), testCase.facet());
        }
    }

    private static FrontendSourceUnit parse(String source, DiagnosticManager diagnostics) {
        return new GdScriptParserService().parseUnit(Path.of("tmp", "annotator.gd"), source, diagnostics);
    }

    private static FrontendAnalysisData annotateParsedUnit(FrontendSourceUnit unit) {
        var data = FrontendAnalysisData.bootstrap();
        for (var recovery : unit.parseRecoveries()) {
            if (recovery.owner() != null) {
                data.parseRecoveriesByOwner().put(recovery.owner(),
                        unit.parseRecoveries().stream().filter(item -> item.owner() == recovery.owner()).toList());
            }
        }
        FrontendErrorSubtreeAnalyzer.annotate(FrontendModule.singleUnit("test_module", unit), data);
        return data;
    }

    private record FacetCase(String facet, Node owner, Node expectedRoot) {
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
        FrontendErrorSubtreeAnalyzer.annotate(new FrontendModule("test_module", List.of(unit)), analysisData);
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

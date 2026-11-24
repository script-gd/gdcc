package gd.script.gdcc.frontend.sema.analyzer;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.BreakStatement;
import dev.superice.gdparser.frontend.ast.ContinueStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.gdscript.DiagnosticCodes;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendLoopControlFlowAnalyzerTest {
    @Test
    void analyzeReportsBreakOutsideLoopAndKeepsOtherFunctionsAlive() throws Exception {
        var result = analyzeWithParserLoopErrors("loop_control_break_outside_loop.gd", """
                class_name LoopControlBreakOutsideLoop
                extends Node
                
                func broken():
                    if true:
                        break
                
                func stable(flag):
                    while flag:
                        continue
                """, false, "break");
        var brokenFunction = findFunction(result.unit().ast().statements(), "broken");
        var stableFunction = findFunction(result.unit().ast().statements(), "stable");
        var breakStatement = findNode(brokenFunction, BreakStatement.class, _ -> true);

        assertEquals(result.rangeOf(breakStatement), result.diagnostics().asList().getFirst().range());
        assertTrue(result.analysisData().scopesByAst().containsKey(stableFunction));
        assertTrue(result.analysisData().scopesByAst().containsKey(stableFunction.body()));
    }

    @Test
    void analyzeReportsContinueOutsideLoopInFunctionBody() throws Exception {
        var result = analyzeWithParserLoopErrors("loop_control_continue_outside_loop.gd", """
                class_name LoopControlContinueOutsideLoop
                extends Node
                
                func ping():
                    continue
                """, false, "continue");
        var pingFunction = findFunction(result.unit().ast().statements(), "ping");
        var continueStatement = findNode(pingFunction, ContinueStatement.class, _ -> true);

        assertEquals(result.rangeOf(continueStatement), result.diagnostics().asList().getFirst().range());
    }

    @Test
    void analyzeAllowsBreakAndContinueInsideNestedIfWithinWhile() throws Exception {
        var result = analyzeShared("loop_control_nested_while.gd", """
                class_name LoopControlNestedWhile
                extends Node
                
                func ping(flag, value):
                    while flag:
                        if value > 0:
                            continue
                        else:
                            break
                """);

        assertTrue(diagnosticsByCategory(result.diagnostics(), "sema.loop_control_flow").isEmpty());
        assertFalse(result.diagnostics().hasErrors());
    }

    @Test
    void analyzeDoesNotReportLoopControlErrorsForValidForLoopBodies() throws Exception {
        var result = analyzeShared("loop_control_for_loop.gd", """
                class_name LoopControlForLoop
                extends Node
                
                func ping(values):
                    for value in values:
                        if value:
                            continue
                        break
                """);

        assertTrue(diagnosticsByCategory(result.diagnostics(), "sema.loop_control_flow").isEmpty());
        assertFalse(result.diagnostics().hasErrors());
    }

    @Test
    void analyzeAllowsBreakAndContinueInsideNestedForAndWhileLoops() throws Exception {
        var result = analyzeShared("loop_control_nested_for_while.gd", """
                class_name LoopControlNestedForWhile
                extends Node
                
                func ping(outer_flag, values, stop_outer):
                    while outer_flag:
                        for value in values:
                            if value:
                                continue
                            break
                        if stop_outer:
                            break
                    for outer in values:
                        while outer_flag:
                            if stop_outer:
                                break
                            continue
                        if outer:
                            continue
                        break
                """);

        assertTrue(diagnosticsByCategory(result.diagnostics(), "sema.loop_control_flow").isEmpty());
        assertFalse(result.diagnostics().hasErrors());
    }

    @Test
    void analyzeAllowsBreakAndContinueInsideNestedWhileLoops() throws Exception {
        var result = analyzeShared("loop_control_nested_while_loops.gd", """
                class_name LoopControlNestedWhileLoops
                extends Node
                
                func ping(outer_flag, inner_flag, stop_inner, skip_inner, stop_outer):
                    while outer_flag:
                        while inner_flag:
                            if stop_inner:
                                break
                            if skip_inner:
                                continue
                        if stop_outer:
                            break
                """);

        assertTrue(diagnosticsByCategory(result.diagnostics(), "sema.loop_control_flow").isEmpty());
        assertFalse(result.diagnostics().hasErrors());
    }

    @Test
    void analyzeAllowsBreakAndContinueInsideMatchSectionWithinOuterLoop() throws Exception {
        var result = analyzeShared("loop_control_match_in_loop.gd", """
                class_name LoopControlMatchInLoop
                extends Node
                
                func ping(values, choice):
                    for value in values:
                        match choice:
                            1:
                                continue
                            _:
                                break
                """);

        assertTrue(diagnosticsByCategory(result.diagnostics(), "sema.loop_control_flow").isEmpty());
        assertFalse(result.diagnostics().hasErrors());
    }

    @Test
    void analyzeReportsBreakAndContinueInsideMatchWithoutOuterLoop() throws Exception {
        var result = analyzeWithParserLoopErrors("loop_control_match_without_loop.gd", """
                class_name LoopControlMatchWithoutLoop
                extends Node
                
                func ping(choice):
                    match choice:
                        1:
                            break
                        _:
                            continue
                """, false, "break", "continue");
        var pingFunction = findFunction(result.unit().ast().statements(), "ping");
        var breakStatement = findNode(pingFunction, BreakStatement.class, _ -> true);
        var continueStatement = findNode(pingFunction, ContinueStatement.class, _ -> true);
        assertEquals(List.of(result.rangeOf(breakStatement), result.rangeOf(continueStatement)),
                result.diagnostics().asList().stream().map(FrontendDiagnostic::range).toList());
    }

    @Test
    void analyzeResetsOuterLoopDepthAtLambdaBoundary() throws Exception {
        var result = analyzeWithParserLoopErrors("loop_control_lambda_boundary.gd", """
                class_name LoopControlLambdaBoundary
                extends Node
                
                func ping(flag):
                    while flag:
                        var callback = func():
                            break
                        return
                """, false, "break");
        var pingFunction = findFunction(result.unit().ast().statements(), "ping");
        var breakStatement = findNode(pingFunction, BreakStatement.class, _ -> true);

        assertEquals(result.rangeOf(breakStatement), result.diagnostics().asList().getFirst().range());
        var fresh = analyzeUnit(result.unit(), new DiagnosticManager(), false);
        assertEquals(1, fresh.diagnostics().asList().size());
        assertEquals("sema.loop_control_flow", fresh.diagnostics().asList().getFirst().category());
        assertEquals(fresh.rangeOf(breakStatement), fresh.diagnostics().asList().getFirst().range());
        assertTrue(fresh.diagnostics().asList().getFirst().message().contains("current callable boundary"));
    }

    @Test
    void analyzeForCompilePreservesSharedLoopControlDiagnosticsWithoutDeferringToCompileGate() throws Exception {
        var source = """
                class_name LoopControlCompileBoundary
                extends Node
                
                func ping():
                    break
                """;

        var shared = analyzeWithParserLoopErrors("loop_control_compile_boundary.gd", source, false, "break");
        var compiled = analyzeWithParserLoopErrors("loop_control_compile_boundary.gd", source, true, "break");
        assertEquals(shared.diagnostics().asList(), compiled.diagnostics().asList());
        var freshShared = analyzeUnit(shared.unit(), new DiagnosticManager(), false);
        var freshCompiled = analyzeUnit(compiled.unit(), new DiagnosticManager(), true);
        assertEquals(1, freshShared.diagnostics().asList().size());
        assertEquals("sema.loop_control_flow", freshShared.diagnostics().asList().getFirst().category());
        assertEquals(freshShared.diagnostics().asList(), freshCompiled.diagnostics().asList());
    }

    @Test
    void manualJumpsAtTheSameRangeKeepDistinctErrorsWhenCompileGateReplaysThem() throws Exception {
        var parseDiagnostics = new DiagnosticManager();
        var healthy = new GdScriptParserService().parseUnit(Path.of("tmp", "manual_loop_control.gd"), """
                func ping() -> void:
                    pass
                """, parseDiagnostics);
        assertTrue(parseDiagnostics.isEmpty());
        var function = assertInstanceOf(FunctionDeclaration.class, healthy.ast().statements().getFirst());
        var range = function.body().statements().getFirst().range();
        var body = new Block(List.of(new BreakStatement(range), new ContinueStatement(range)), function.body().range());
        var wrapper = new FunctionDeclaration(function.name(), function.parameters(), function.returnType(),
                function.isStatic(), body, function.range());
        var unit = new FrontendSourceUnit(healthy.path(), healthy.source(), new SourceFile(List.of(wrapper), healthy.ast().range()));
        var analyzed = analyzeUnit(unit, new DiagnosticManager(), false);
        var expected = analyzed.diagnostics().asList();
        assertEquals(2, expected.size());
        assertTrue(expected.stream().allMatch(d -> d.category().equals("sema.loop_control_flow")
                && FrontendRange.fromAstRange(range).equals(d.range()) && d.severity() == FrontendDiagnosticSeverity.ERROR));
        assertTrue(expected.getFirst().message().contains("`break`"));
        assertTrue(expected.getLast().message().contains("`continue`"));
        assertTrue(analyzed.analysisData().claimedParseRecoveries().isEmpty());
        var replay = new DiagnosticManager();
        new FrontendCompileCheckAnalyzer().analyze(analyzed.analysisData(), replay);
        assertEquals(expected, replay.snapshot().asList());
    }

    private static @NotNull AnalyzedScript analyzeShared(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        var parseDiagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(Path.of("tmp", fileName), source, parseDiagnostics);
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());
        return analyzeUnit(unit, parseDiagnostics, false);
    }

    private static @NotNull AnalyzedScript analyzeWithParserLoopErrors(
            @NotNull String fileName,
            @NotNull String source,
            boolean compileMode,
            String... keywords
    ) throws Exception {
        var manager = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(Path.of("tmp", fileName), source, manager);
        var original = manager.snapshot().asList();
        assertEquals(keywords.length, original.size());
        assertEquals(keywords.length, unit.parseRecoveries().size());
        for (var index = 0; index < keywords.length; index++) {
            var keyword = keywords[index];
            var recovery = unit.parseRecoveries().get(index);
            if (keyword.equals("break")) {
                assertInstanceOf(BreakStatement.class, recovery.owner());
            } else {
                assertInstanceOf(ContinueStatement.class, recovery.owner());
            }
            assertEquals("statement.control", recovery.facet());
            assertEquals(DiagnosticCodes.SYNTAX_UNEXPECTED_TOKEN, original.get(index).category());
            assertEquals("Cannot use \"" + keyword + "\" outside of a loop.", original.get(index).message());
        }
        var analyzed = analyzeUnit(unit, manager, compileMode);
        assertEquals(original, analyzed.diagnostics().asList());
        for (var index = 0; index < keywords.length; index++) {
            assertEquals(original.get(index),
                    analyzed.analysisData().claimedParseRecoveries().get(unit.parseRecoveries().get(index)));
        }
        return analyzed;
    }

    private static @NotNull AnalyzedScript analyzeUnit(
            @NotNull FrontendSourceUnit unit,
            @NotNull DiagnosticManager diagnosticManager,
            boolean compileMode
    ) throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analyzer = new FrontendSemanticAnalyzer();
        var analysisData = compileMode
                ? analyzer.analyzeForCompile(new FrontendModule("test_module", List.of(unit)), registry, diagnosticManager)
                : analyzer.analyze(new FrontendModule("test_module", List.of(unit)), registry, diagnosticManager);
        return new AnalyzedScript(unit, analysisData, diagnosticManager.snapshot());
    }

    private static @NotNull List<FrontendDiagnostic> diagnosticsByCategory(
            @NotNull DiagnosticSnapshot diagnostics,
            @NotNull String category
    ) {
        return diagnostics.asList().stream()
                .filter(diagnostic -> diagnostic.category().equals(category))
                .toList();
    }

    private static @NotNull FunctionDeclaration findFunction(
            @NotNull List<Statement> statements,
            @NotNull String functionName
    ) {
        for (var statement : statements) {
            if (statement instanceof FunctionDeclaration functionDeclaration
                    && functionDeclaration.name().equals(functionName)) {
                return functionDeclaration;
            }
        }
        throw new AssertionError("Function not found: " + functionName);
    }

    private static <T extends Node> @NotNull T findNode(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        if (nodeType.isInstance(root)) {
            var candidate = nodeType.cast(root);
            if (predicate.test(candidate)) {
                return candidate;
            }
        }
        for (var child : root.getChildren()) {
            try {
                return findNode(child, nodeType, predicate);
            } catch (AssertionError ignored) {
                // Continue searching remaining subtrees.
            }
        }
        throw new AssertionError("Node not found: " + nodeType.getSimpleName());
    }

    private record AnalyzedScript(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticSnapshot diagnostics
    ) {
        private AnalyzedScript {
            Objects.requireNonNull(unit, "unit must not be null");
            Objects.requireNonNull(analysisData, "analysisData must not be null");
            Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        }

        private FrontendRange rangeOf(@NotNull Node node) {
            return FrontendRange.fromAstRange(node.range());
        }
    }
}

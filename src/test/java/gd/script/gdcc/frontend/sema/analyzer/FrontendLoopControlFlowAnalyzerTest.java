package gd.script.gdcc.frontend.sema.analyzer;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import dev.superice.gdparser.frontend.ast.BreakStatement;
import dev.superice.gdparser.frontend.ast.ContinueStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Statement;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendLoopControlFlowAnalyzerTest {
    @Test
    void analyzeReportsBreakOutsideLoopAndKeepsOtherFunctionsAlive() throws Exception {
        var result = analyzeShared("loop_control_break_outside_loop.gd", """
                class_name LoopControlBreakOutsideLoop
                extends Node
                
                func broken():
                    if true:
                        break
                
                func stable(flag):
                    while flag:
                        continue
                """);
        var brokenFunction = findFunction(result.unit().ast().statements(), "broken");
        var stableFunction = findFunction(result.unit().ast().statements(), "stable");
        var breakStatement = findNode(brokenFunction, BreakStatement.class, _ -> true);

        var loopDiagnostics = diagnosticsByCategory(result.diagnostics(), "sema.loop_control_flow");

        assertTrue(result.diagnostics().hasErrors());
        assertEquals(1, loopDiagnostics.size());
        assertEquals(result.rangeOf(breakStatement), loopDiagnostics.getFirst().range());
        assertTrue(loopDiagnostics.getFirst().message().contains("`break`"));
        assertTrue(result.analysisData().scopesByAst().containsKey(stableFunction));
        assertTrue(result.analysisData().scopesByAst().containsKey(stableFunction.body()));
    }

    @Test
    void analyzeReportsContinueOutsideLoopInFunctionBody() throws Exception {
        var result = analyzeShared("loop_control_continue_outside_loop.gd", """
                class_name LoopControlContinueOutsideLoop
                extends Node
                
                func ping():
                    continue
                """);
        var pingFunction = findFunction(result.unit().ast().statements(), "ping");
        var continueStatement = findNode(pingFunction, ContinueStatement.class, _ -> true);

        var loopDiagnostics = diagnosticsByCategory(result.diagnostics(), "sema.loop_control_flow");

        assertTrue(result.diagnostics().hasErrors());
        assertEquals(1, loopDiagnostics.size());
        assertEquals(result.rangeOf(continueStatement), loopDiagnostics.getFirst().range());
        assertTrue(loopDiagnostics.getFirst().message().contains("`continue`"));
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
    void analyzeResetsOuterLoopDepthAtLambdaBoundary() throws Exception {
        var result = analyzeShared("loop_control_lambda_boundary.gd", """
                class_name LoopControlLambdaBoundary
                extends Node
                
                func ping(flag):
                    while flag:
                        var callback = func():
                            break
                        return
                """);
        var pingFunction = findFunction(result.unit().ast().statements(), "ping");
        var breakStatement = findNode(pingFunction, BreakStatement.class, _ -> true);

        var loopDiagnostics = diagnosticsByCategory(result.diagnostics(), "sema.loop_control_flow");

        assertTrue(result.diagnostics().hasErrors());
        assertEquals(1, loopDiagnostics.size());
        assertEquals(result.rangeOf(breakStatement), loopDiagnostics.getFirst().range());
        assertTrue(loopDiagnostics.getFirst().message().contains("current callable boundary"));
    }

    @Test
    void analyzeForCompilePreservesSharedLoopControlDiagnosticsWithoutDeferringToCompileGate() throws Exception {
        var source = """
                class_name LoopControlCompileBoundary
                extends Node
                
                func ping():
                    break
                """;

        var shared = analyzeShared("loop_control_compile_boundary.gd", source);
        var compiled = analyzeForCompile("loop_control_compile_boundary.gd", source);
        var sharedLoopDiagnostics = diagnosticsByCategory(shared.diagnostics(), "sema.loop_control_flow");
        var compiledLoopDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.loop_control_flow");

        assertEquals(1, sharedLoopDiagnostics.size());
        assertEquals(1, compiledLoopDiagnostics.size());
        assertEquals(sharedLoopDiagnostics.getFirst().range(), compiledLoopDiagnostics.getFirst().range());
        assertEquals(sharedLoopDiagnostics.getFirst().message(), compiledLoopDiagnostics.getFirst().message());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
    }

    private static @NotNull AnalyzedScript analyzeShared(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        return analyze(fileName, source, false);
    }

    private static @NotNull AnalyzedScript analyzeForCompile(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        return analyze(fileName, source, true);
    }

    private static @NotNull AnalyzedScript analyze(
            @NotNull String fileName,
            @NotNull String source,
            boolean compileMode
    ) throws Exception {
        var parserService = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, parseDiagnostics);
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());

        var diagnosticManager = new DiagnosticManager();
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

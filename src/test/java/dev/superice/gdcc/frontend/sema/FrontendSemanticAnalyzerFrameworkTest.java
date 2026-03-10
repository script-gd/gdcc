package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendSemanticAnalyzerFrameworkTest {
    @Test
    void analyzeBootstrapsSideTablesAndCollectsSemanticallyRelevantAnnotations() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "annotated_player.gd"), """
                @tool
                class_name AnnotatedPlayer
                extends Node
                
                @export var hp: int = 1
                
                @rpc("authority")
                func ping(value):
                    pass
                
                @warning_ignore_start("unused_variable")
                var tmp := 1
                
                @warning_ignore_restore("unused_variable")
                var keep := 2
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analyzer = new FrontendSemanticAnalyzer();

        var result = analyzer.analyze("test_module", List.of(unit), registry, diagnostics);
        var topLevelStatements = unit.ast().statements();
        var hpProperty = findVariable(topLevelStatements, "hp");
        var tmpProperty = findVariable(topLevelStatements, "tmp");
        var keepProperty = findVariable(topLevelStatements, "keep");
        var pingFunction = findFunction(topLevelStatements, "ping");

        assertEquals(1, result.moduleSkeleton().classDefs().size());
        assertEquals("AnnotatedPlayer", result.moduleSkeleton().classDefs().getFirst().getName());
        assertEquals(List.of("tool"), annotationNames(result.annotationsByAst().get(unit.ast())));
        assertEquals(List.of("export"), annotationNames(result.annotationsByAst().get(hpProperty)));
        assertEquals(List.of("rpc"), annotationNames(result.annotationsByAst().get(pingFunction)));
        assertNull(result.annotationsByAst().get(tmpProperty));
        assertNull(result.annotationsByAst().get(keepProperty));

        assertTrue(result.scopesByAst().isEmpty());
        assertTrue(result.symbolBindings().isEmpty());
        assertTrue(result.expressionTypes().isEmpty());
        assertTrue(result.resolvedMembers().isEmpty());
        assertTrue(result.resolvedCalls().isEmpty());
        assertNotNull(result.diagnostics());
        assertEquals(diagnostics.snapshot(), result.diagnostics());
        assertEquals(result.moduleSkeleton().diagnostics(), result.diagnostics());
    }

    @Test
    void analyzeCollectsNestedBlockAnnotationsAndStillIgnoresRegionAnnotations() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "nested_annotations.gd"), """
                class_name NestedAnnotations
                extends Node
                
                func ping(value):
                    @warning_ignore("unused_variable")
                    var inner := 1
                    @warning_ignore_start("unused_variable")
                    var region_ignored := 2
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analyzer = new FrontendSemanticAnalyzer();

        var result = analyzer.analyze("test_module", List.of(unit), registry, diagnostics);
        var pingFunction = findFunction(unit.ast().statements(), "ping");
        var bodyStatements = pingFunction.body().statements();
        var innerVariable = findVariable(bodyStatements, "inner");
        var regionIgnoredVariable = findVariable(bodyStatements, "region_ignored");

        assertEquals(diagnostics.snapshot(), result.diagnostics());
        assertEquals(List.of("warning_ignore"), annotationNames(result.annotationsByAst().get(innerVariable)));
        assertNull(result.annotationsByAst().get(regionIgnoredVariable));
    }

    @Test
    void analyzeDoesNotInventParseDiagnosticsForManualUnitsOutsideSharedManagerPipeline() throws Exception {
        var parserService = new GdScriptParserService();
        var parsed = parserService.parseUnit(Path.of("tmp", "manual_unit.gd"), """
                class_name ManualUnit
                extends Node
                
                func ping():
                    pass
                """, new DiagnosticManager());
        var unit = new FrontendSourceUnit(
                parsed.path(),
                parsed.source(),
                parsed.ast()
        );
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analyzer = new FrontendSemanticAnalyzer();
        var diagnostics = new DiagnosticManager();

        var result = analyzer.analyze("test_module", List.of(unit), registry, diagnostics);

        assertEquals(1, result.moduleSkeleton().classDefs().size());
        assertTrue(diagnostics.isEmpty());
        assertTrue(result.moduleSkeleton().diagnostics().isEmpty());
        assertTrue(result.diagnostics().isEmpty());
    }

    /// Anchors the phase-boundary snapshot rule: the analysis data captures the shared
    /// manager state once, and later manager mutations must not retroactively rewrite either
    /// `FrontendAnalysisData` or its nested `FrontendModuleSkeleton`.
    @Test
    void analyzePublishesStableDiagnosticsSnapshotEvenIfManagerChangesLater() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "stable_snapshot.gd"), """
                class_name StableSnapshot
                extends Node
                
                func _ready(
                    pass
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analyzer = new FrontendSemanticAnalyzer();

        var result = analyzer.analyze("test_module", List.of(unit), registry, diagnostics);
        var parseSnapshot = diagnostics.snapshot();
        var beforeMutation = result.diagnostics();
        diagnostics.error("sema.synthetic", "late diagnostic", unit.path(), null);

        assertFalse(beforeMutation.isEmpty());
        assertEquals(parseSnapshot.asList(), beforeMutation.asList());
        assertEquals(beforeMutation, result.diagnostics());
        assertEquals(beforeMutation, result.moduleSkeleton().diagnostics());
        assertEquals(beforeMutation.size() + 1, diagnostics.snapshot().size());
    }

    private VariableDeclaration findVariable(List<?> statements, String name) {
        return statements.stream()
                .filter(VariableDeclaration.class::isInstance)
                .map(VariableDeclaration.class::cast)
                .filter(variableDeclaration -> variableDeclaration.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Variable not found: " + name));
    }

    private FunctionDeclaration findFunction(List<?> statements, String name) {
        return statements.stream()
                .filter(FunctionDeclaration.class::isInstance)
                .map(FunctionDeclaration.class::cast)
                .filter(functionDeclaration -> functionDeclaration.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Function not found: " + name));
    }

    private List<String> annotationNames(List<FrontendGdAnnotation> annotations) {
        assertNotNull(annotations);
        return annotations.stream().map(FrontendGdAnnotation::name).toList();
    }
}

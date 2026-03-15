package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.scope.BlockScope;
import dev.superice.gdcc.frontend.scope.CallableScope;
import dev.superice.gdcc.frontend.scope.ClassScope;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendScopeAnalyzer;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendVariableAnalyzer;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendVariableAnalyzerTest {
    @Test
    void analyzeRejectsAnalysisDataWithoutPublishedModuleSkeletonBoundary() {
        var analyzer = new FrontendVariableAnalyzer();
        var diagnostics = new DiagnosticManager();

        assertThrows(
                IllegalStateException.class,
                () -> analyzer.analyze(FrontendAnalysisData.bootstrap(), diagnostics)
        );
    }

    @Test
    void analyzeRejectsAnalysisDataWithoutPublishedPreVariableDiagnosticsBoundary() {
        var analyzer = new FrontendVariableAnalyzer();
        var analysisData = FrontendAnalysisData.bootstrap();
        var diagnostics = new DiagnosticManager();
        analysisData.updateModuleSkeleton(new FrontendModuleSkeleton("test_module", List.of(), diagnostics.snapshot()));

        assertThrows(IllegalStateException.class, () -> analyzer.analyze(analysisData, diagnostics));
    }

    @Test
    void analyzeRejectsAcceptedSourcesBeforeScopePhasePublishesTopLevelScopes() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "missing_scope_boundary.gd"), """
                class_name MissingScopeBoundary
                extends Node
                
                func ping(value):
                    pass
                """, diagnostics);
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());

        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = FrontendAnalysisData.bootstrap();
        var moduleSkeleton = new FrontendClassSkeletonBuilder().build(
                "test_module",
                List.of(unit),
                registry,
                diagnostics,
                analysisData
        );
        analysisData.updateModuleSkeleton(moduleSkeleton);
        analysisData.updateDiagnostics(diagnostics.snapshot());

        var error = assertThrows(
                IllegalStateException.class,
                () -> new FrontendVariableAnalyzer().analyze(analysisData, diagnostics)
        );
        assertTrue(error.getMessage().contains("Scope graph has not been published"));
        assertTrue(error.getMessage().contains(unit.path().toString()));
    }

    @Test
    void analyzeKeepsPublishedScopeFactsStableWhileConcreteBindingWorkIsStillDeferred() throws Exception {
        var phaseInput = publishedPhaseInput("""
                class_name VariablePhaseBoundary
                extends Node
                
                func ping(value):
                    var local := value
                    return local
                """);
        var analysisData = phaseInput.analysisData();
        var scopesByAst = analysisData.scopesByAst();
        var sourceFile = phaseInput.unit().ast();
        var pingFunction = sourceFile.statements().stream()
                .filter(dev.superice.gdparser.frontend.ast.FunctionDeclaration.class::isInstance)
                .map(dev.superice.gdparser.frontend.ast.FunctionDeclaration.class::cast)
                .filter(functionDeclaration -> functionDeclaration.name().equals("ping"))
                .findFirst()
                .orElseThrow();

        var sourceScope = assertInstanceOf(ClassScope.class, scopesByAst.get(sourceFile));
        var pingScope = assertInstanceOf(CallableScope.class, scopesByAst.get(pingFunction));
        var bodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(pingFunction.body()));

        new FrontendVariableAnalyzer().analyze(analysisData, phaseInput.diagnostics());

        assertSame(scopesByAst, analysisData.scopesByAst());
        assertSame(sourceScope, analysisData.scopesByAst().get(sourceFile));
        assertSame(pingScope, analysisData.scopesByAst().get(pingFunction));
        assertSame(bodyScope, analysisData.scopesByAst().get(pingFunction.body()));
        assertNull(pingScope.resolveValue("value"));
        assertNull(bodyScope.resolveValue("local"));
        assertTrue(analysisData.symbolBindings().isEmpty());
        assertTrue(analysisData.expressionTypes().isEmpty());
        assertTrue(analysisData.resolvedMembers().isEmpty());
        assertTrue(analysisData.resolvedCalls().isEmpty());
        assertEquals(phaseInput.diagnostics().snapshot(), analysisData.diagnostics());
    }

    @Test
    void analyzeOnlyExposesManagerAwarePublicEntryPoint() {
        var analyzeMethods = Arrays.stream(FrontendVariableAnalyzer.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getName().equals("analyze"))
                .toList();

        assertEquals(1, analyzeMethods.size());
        assertArrayEquals(
                new Class<?>[]{FrontendAnalysisData.class, DiagnosticManager.class},
                analyzeMethods.getFirst().getParameterTypes()
        );
    }

    private PhaseInput publishedPhaseInput(String source) throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "frontend_variable_analyzer_test.gd"), source, diagnostics);
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());

        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = FrontendAnalysisData.bootstrap();
        var moduleSkeleton = new FrontendClassSkeletonBuilder().build(
                "test_module",
                List.of(unit),
                registry,
                diagnostics,
                analysisData
        );
        analysisData.updateModuleSkeleton(moduleSkeleton);
        analysisData.updateDiagnostics(diagnostics.snapshot());
        new FrontendScopeAnalyzer().analyze(registry, analysisData, diagnostics);
        analysisData.updateDiagnostics(diagnostics.snapshot());
        return new PhaseInput(unit, analysisData, diagnostics);
    }

    private record PhaseInput(
            FrontendSourceUnit unit,
            FrontendAnalysisData analysisData,
            DiagnosticManager diagnostics
    ) {
    }
}

package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendBinding;
import dev.superice.gdcc.frontend.sema.FrontendBindingKind;
import dev.superice.gdcc.frontend.sema.FrontendClassSkeletonBuilder;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendTopBindingAnalyzerTest {
    @Test
    void analyzeRejectsMissingModuleSkeletonBoundary() {
        var analyzer = new FrontendTopBindingAnalyzer();
        var analysisData = FrontendAnalysisData.bootstrap();

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> analyzer.analyze(analysisData, new DiagnosticManager())
        );

        assertTrue(thrown.getMessage().contains("moduleSkeleton"));
    }

    @Test
    void analyzeRejectsMissingDiagnosticsBoundary() throws Exception {
        var preparedInput = prepareBindingInput("missing_binding_diagnostics.gd", """
                class_name MissingBindingDiagnostics
                extends Node
                
                func ping():
                    pass
                """);
        var analyzer = new FrontendTopBindingAnalyzer();
        var analysisData = FrontendAnalysisData.bootstrap();
        analysisData.updateModuleSkeleton(preparedInput.analysisData().moduleSkeleton());

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> analyzer.analyze(analysisData, preparedInput.diagnosticManager())
        );

        assertTrue(thrown.getMessage().contains("diagnostics"));
    }

    @Test
    void analyzeRejectsMissingPublishedSourceScope() throws Exception {
        var preparedInput = prepareBindingInput("missing_source_scope.gd", """
                class_name MissingSourceScope
                extends Node
                
                func ping():
                    pass
                """);
        var analyzer = new FrontendTopBindingAnalyzer();
        preparedInput.analysisData().scopesByAst().remove(preparedInput.unit().ast());

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager())
        );

        assertTrue(thrown.getMessage().contains(preparedInput.unit().path().toString()));
    }

    @Test
    void analyzePublishesEmptySymbolBindingsAndClearsStaleEntries() throws Exception {
        var preparedInput = prepareBindingInput("publish_empty_symbol_bindings.gd", """
                class_name PublishEmptySymbolBindings
                extends Node
                
                func ping(value):
                    var alias = value
                """);
        var analyzer = new FrontendTopBindingAnalyzer();
        var publishedSymbolBindings = preparedInput.analysisData().symbolBindings();
        var staleNode = preparedInput.unit().ast();
        publishedSymbolBindings.put(staleNode, new FrontendBinding("__stale__", FrontendBindingKind.UNKNOWN, null));

        analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager());

        assertSame(publishedSymbolBindings, preparedInput.analysisData().symbolBindings());
        assertTrue(preparedInput.analysisData().symbolBindings().isEmpty());
        assertNull(preparedInput.analysisData().symbolBindings().get(staleNode));
        assertTrue(preparedInput.analysisData().resolvedMembers().isEmpty());
        assertTrue(preparedInput.analysisData().resolvedCalls().isEmpty());
        assertTrue(preparedInput.analysisData().expressionTypes().isEmpty());
    }

    @Test
    void analyzeTraversesRepresentativeSupportedAndDeferredSyntaxWithoutPublishingBindingsInStageB1()
            throws Exception {
        var preparedInput = prepareBindingInput("binding_traversal_shape.gd", """
                class_name BindingTraversalShape
                extends Node
                
                var hp = 1
                
                class Inner:
                    static func build():
                        return null
                
                func ping(value, alias = value):
                    var seed = 1
                    var copied = value
                    print(seed)
                    assert(seed > 0, "ok")
                    self.hp
                    Inner.build()
                    value.to_string()
                    value[seed + 1]
                    if seed > 0:
                        print(Inner.build())
                    elif copied:
                        print(self)
                    else:
                        var branch = [seed, 0, "txt", true]
                    while seed > 0:
                        seed -= 1
                    const blocked = seed
                    var closure = func():
                        return seed
                    for item in [seed]:
                        print(item)
                    match value:
                        var bound when bound > 0:
                            print(bound)
                """);
        var analyzer = new FrontendTopBindingAnalyzer();
        var diagnosticsBeforeBinding = preparedInput.diagnosticManager().snapshot();

        analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager());

        assertTrue(preparedInput.analysisData().symbolBindings().isEmpty());
        assertEquals(diagnosticsBeforeBinding, preparedInput.diagnosticManager().snapshot());
        assertTrue(!diagnosticsBeforeBinding.isEmpty());
    }

    private static @NotNull PreparedBindingInput prepareBindingInput(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        var parserService = new GdScriptParserService();
        var diagnosticManager = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnosticManager);
        assertTrue(diagnosticManager.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnosticManager.snapshot());

        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = FrontendAnalysisData.bootstrap();
        var moduleSkeleton = new FrontendClassSkeletonBuilder().build(
                "test_module",
                List.of(unit),
                classRegistry,
                diagnosticManager,
                analysisData
        );
        analysisData.updateModuleSkeleton(moduleSkeleton);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        new FrontendScopeAnalyzer().analyze(classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        new FrontendVariableAnalyzer().analyze(analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        return new PreparedBindingInput(unit, analysisData, diagnosticManager);
    }

    private record PreparedBindingInput(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
    }
}

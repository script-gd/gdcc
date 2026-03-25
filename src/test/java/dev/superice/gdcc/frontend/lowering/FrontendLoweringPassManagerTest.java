package dev.superice.gdcc.frontend.lowering;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendLoweringPassManagerTest {
    @Test
    void lowerIsTheOnlyDeclaredPublicMethod() {
        var publicMethodNames = Stream.of(FrontendLoweringPassManager.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .toList();

        assertEquals(List.of("lower"), publicMethodNames);
    }

    @Test
    void lowerRunsPassesInOrderAndPreservesSharedContextState() throws Exception {
        var module = new FrontendModule("test_module", List.of());
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var executionOrder = new ArrayList<String>();
        var publishedAnalysisData = new AtomicReference<dev.superice.gdcc.frontend.sema.FrontendAnalysisData>();
        var expectedLirModule = new LirModule("test_module", List.of());
        var manager = new FrontendLoweringPassManager(List.of(
                context -> {
                    executionOrder.add("analysis");
                    assertNull(context.analysisDataOrNull());
                    var analysisData = dev.superice.gdcc.frontend.sema.FrontendAnalysisData.bootstrap();
                    context.publishAnalysisData(analysisData);
                    publishedAnalysisData.set(analysisData);
                },
                context -> {
                    executionOrder.add("emit");
                    assertSame(publishedAnalysisData.get(), context.analysisDataOrNull());
                    assertFalse(context.isStopRequested());
                    context.publishLirModule(expectedLirModule);
                }
        ));

        var lowered = manager.lower(module, registry, diagnostics);

        assertSame(expectedLirModule, lowered);
        assertEquals(List.of("analysis", "emit"), executionOrder);
    }

    @Test
    void lowerStopsAfterPassRequestsStop() throws Exception {
        var module = new FrontendModule("test_module", List.of());
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var executionOrder = new ArrayList<String>();
        var manager = new FrontendLoweringPassManager(List.of(
                context -> {
                    executionOrder.add("first");
                    context.requestStop();
                },
                _ -> executionOrder.add("second")
        ));

        var lowered = manager.lower(module, registry, diagnostics);

        assertNull(lowered);
        assertEquals(List.of("first"), executionOrder);
    }

    @Test
    void lowerReturnsNullWhenDefaultPipelinePublishesNoLirModuleYet() throws Exception {
        var diagnostics = new DiagnosticManager();
        var lowered = new FrontendLoweringPassManager().lower(
                parseModule(
                        "lowering_manager_compile_ready.gd",
                        """
                                class_name LoweringManagerCompileReady
                                extends RefCounted
                                
                                func ping():
                                    pass
                                """
                ),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );

        assertNull(lowered);
        assertFalse(diagnostics.hasErrors());
    }

    private static @NotNull FrontendModule parseModule(
            @NotNull String fileName,
            @NotNull String source
    ) {
        var parserService = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, parseDiagnostics);
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());
        return new FrontendModule("test_module", List.of(unit));
    }
}

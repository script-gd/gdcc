package dev.superice.gdcc.frontend.lowering;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.parser.DomLirSerializer;
import dev.superice.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void lowerCompileReadyModuleReturnsSerializableSkeletonOnlyLirModule() throws Exception {
        var diagnostics = new DiagnosticManager();
        var lowered = new FrontendLoweringPassManager().lower(
                parseModule(
                        List.of(new SourceFixture(
                                "lowering_manager_compile_ready.gd",
                                """
                                        class_name MappedOuter
                                        extends RefCounted
                                        
                                        signal changed(value: int)
                                        var count: int = 1
                                        
                                        func ping(value: int) -> int:
                                            return value
                                        
                                        class Inner:
                                            extends RefCounted
                                        
                                            func pong() -> void:
                                                pass
                                        """
                        )),
                        Map.of("MappedOuter", "RuntimeOuter")
                ),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );

        assertNotNull(lowered);
        assertEquals("test_module", lowered.getModuleName());
        assertFalse(diagnostics.hasErrors());
        assertEquals(List.of("RuntimeOuter", "RuntimeOuter$Inner"), lowered.getClassDefs().stream().map(LirClassDef::getName).toList());

        var xml = new DomLirSerializer().serializeToString(lowered);
        assertTrue(xml.contains("name=\"RuntimeOuter\""), xml);
        assertTrue(xml.contains("name=\"RuntimeOuter$Inner\""), xml);
        assertTrue(xml.contains("<signal name=\"changed\">"), xml);
        assertTrue(xml.contains("name=\"count\""), xml);
        assertTrue(xml.contains("type=\"int\""), xml);
        assertTrue(xml.contains("name=\"_field_init_count\""), xml);
        assertFalse(xml.contains("<basic_block id="), xml);
        assertFalse(xml.contains("<basic_blocks entry="), xml);
    }

    @Test
    void lowerModuleWithPropertyWithoutInitializerDoesNotPublishFrontendInitShellIntoSerializedLir() throws Exception {
        var diagnostics = new DiagnosticManager();
        var lowered = new FrontendLoweringPassManager().lower(
                parseModule(
                        List.of(new SourceFixture(
                                "lowering_manager_no_property_initializer.gd",
                                """
                                        class_name NoInitializerOuter
                                        extends RefCounted
                                        
                                        var plain_count: int
                                        
                                        func ping() -> void:
                                            pass
                                        """
                        )),
                        Map.of("NoInitializerOuter", "RuntimeNoInitializerOuter")
                ),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );

        assertNotNull(lowered);
        assertFalse(diagnostics.hasErrors());

        var xml = new DomLirSerializer().serializeToString(lowered);
        assertTrue(xml.contains("name=\"plain_count\""), xml);
        assertFalse(xml.contains("init_func=\"_field_init_plain_count\""), xml);
        assertFalse(xml.contains("name=\"_field_init_plain_count\""), xml);
    }

    @Test
    void lowerCompileBlockedModuleReturnsNullAndKeepsDiagnostics() throws Exception {
        var diagnostics = new DiagnosticManager();
        var lowered = new FrontendLoweringPassManager().lower(
                parseModule(
                        List.of(new SourceFixture(
                                "lowering_manager_compile_blocked.gd",
                                """
                                        class_name LoweringManagerCompileBlocked
                                        extends RefCounted
                                        
                                        func ping(value):
                                            assert(value, "blocked in compile mode")
                                        """
                        )),
                        Map.of()
                ),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );

        assertNull(lowered);
        assertTrue(diagnostics.hasErrors());
        var compileDiagnostics = diagnostics.snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.compile_check"))
                .toList();
        assertFalse(compileDiagnostics.isEmpty());
        assertTrue(
                compileDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("assert statement")),
                () -> "Unexpected diagnostics: " + diagnostics.snapshot()
        );
    }

    private static @NotNull FrontendModule parseModule(
            @NotNull List<SourceFixture> fixtures,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        var parserService = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var units = fixtures.stream()
                .map(fixture -> parserService.parseUnit(Path.of("tmp", fixture.fileName()), fixture.source(), parseDiagnostics))
                .toList();
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());
        return new FrontendModule("test_module", units, topLevelCanonicalNameMap);
    }

    private record SourceFixture(
            @NotNull String fileName,
            @NotNull String source
    ) {
    }
}

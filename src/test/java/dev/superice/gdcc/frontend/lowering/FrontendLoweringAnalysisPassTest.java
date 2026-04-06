package dev.superice.gdcc.frontend.lowering;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringAnalysisPass;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendVarTypePostAnalyzer;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendLoweringAnalysisPassTest {
    @Test
    void lowerCompileReadyModulePublishesAnalysisDataAndKeepsPipelineRunning() throws Exception {
        var module = parseModule(
                "lowering_compile_ready.gd",
                """
                        class_name LoweringCompileReady
                        extends RefCounted
                        
                        func ping() -> void:
                            pass
                        """
        );
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var publishedAnalysisData = new AtomicReference<dev.superice.gdcc.frontend.sema.FrontendAnalysisData>();
        var continuationRan = new AtomicBoolean();
        var manager = new FrontendLoweringPassManager(List.of(
                new FrontendLoweringAnalysisPass(),
                context -> {
                    continuationRan.set(true);
                    assertNotNull(context.analysisDataOrNull());
                    assertFalse(context.isStopRequested());
                    assertNull(context.lirModuleOrNull());
                    publishedAnalysisData.set(context.requireAnalysisData());
                }
        ));

        var lowered = manager.lower(module, registry, diagnostics);

        assertNull(lowered);
        assertTrue(continuationRan.get());
        assertNotNull(publishedAnalysisData.get());
        assertEquals("test_module", publishedAnalysisData.get().moduleSkeleton().moduleName());
        assertFalse(publishedAnalysisData.get().diagnostics().hasErrors());
        assertFalse(diagnostics.hasErrors());
    }

    @Test
    void lowerCompileBlockedModulesStopAfterAnalysisPassAndKeepCompileCheckDiagnostics() throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        for (var testCase : List.of(
                new CompileBlockedCase(
                        "lowering_blocked_assert.gd",
                        """
                                class_name LoweringBlockedAssert
                                extends RefCounted
                                
                                func ping(value):
                                    assert(value, "blocked in compile mode")
                                """,
                        "assert statement"
                ),
                new CompileBlockedCase(
                        "lowering_blocked_conditional.gd",
                        """
                                class_name LoweringBlockedConditional
                                extends RefCounted
                                
                                func ping(value):
                                    return 1 if value else 0
                                """,
                        "Conditional expression"
                ),
                new CompileBlockedCase(
                        "lowering_blocked_static_property.gd",
                        """
                                class_name LoweringBlockedStaticProperty
                                extends RefCounted
                                
                                static var shared := 1
                                """,
                        "Static property 'shared'"
                )
        )) {
            var module = parseModule(testCase.fileName(), testCase.source());
            var diagnostics = new DiagnosticManager();
            var continuationRan = new AtomicBoolean();
            var manager = new FrontendLoweringPassManager(List.of(
                    new FrontendLoweringAnalysisPass(),
                    _ -> continuationRan.set(true)
            ));

            var lowered = manager.lower(module, registry, diagnostics);

            assertNull(lowered, testCase.fileName());
            assertFalse(continuationRan.get(), testCase.fileName());
            assertTrue(diagnostics.hasErrors(), testCase.fileName());
            var compileDiagnostics = diagnostics.snapshot().asList().stream()
                    .filter(diagnostic -> diagnostic.category().equals("sema.compile_check"))
                    .toList();
            assertFalse(compileDiagnostics.isEmpty(), testCase.fileName());
            assertTrue(
                    compileDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains(testCase.expectedMessage())),
                    testCase.fileName()
            );
        }
    }

    @Test
    void lowerVariableConflictModuleStopsAfterAnalysisPassBeforeAnyLoweringPassRuns() throws Exception {
        var module = parseModule(
                "lowering_blocked_duplicate_local.gd",
                """
                        class_name LoweringBlockedDuplicateLocal
                        extends RefCounted
                        
                        func ping():
                            var value := 1
                            var value := 2
                            return value
                        """
        );
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var continuationRan = new AtomicBoolean();
        var manager = new FrontendLoweringPassManager(List.of(
                new FrontendLoweringAnalysisPass(),
                _ -> continuationRan.set(true)
        ));

        var lowered = manager.lower(module, registry, diagnostics);
        var compileDiagnostics = diagnostics.snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.compile_check"))
                .toList();
        var variableDiagnostics = diagnostics.snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.variable_binding"))
                .toList();
        var slotPublicationWarnings = diagnostics.snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals(FrontendVarTypePostAnalyzer.VARIABLE_SLOT_PUBLICATION_CATEGORY))
                .toList();

        assertNull(lowered);
        assertFalse(continuationRan.get());
        assertTrue(diagnostics.hasErrors());
        assertEquals(1, variableDiagnostics.size());
        assertEquals(1, slotPublicationWarnings.size());
        assertEquals(1, compileDiagnostics.size());
        assertTrue(variableDiagnostics.getFirst().message().contains("Duplicate local variable 'value'"));
        assertTrue(compileDiagnostics.getFirst().message().contains("missing a lowering-ready published slot type"));
    }

    @Test
    void lowerTypeCheckBlockedModuleStopsAfterAnalysisPassBeforeAnyLoweringPassRuns() throws Exception {
        var module = parseModule(
                "lowering_blocked_bare_return.gd",
                """
                        class_name LoweringBlockedBareReturn
                        extends RefCounted
                        
                        func ping() -> Object:
                            return
                        """
        );
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var continuationRan = new AtomicBoolean();
        var manager = new FrontendLoweringPassManager(List.of(
                new FrontendLoweringAnalysisPass(),
                _ -> continuationRan.set(true)
        ));

        var lowered = manager.lower(module, registry, diagnostics);
        var typeCheckDiagnostics = diagnostics.snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.type_check"))
                .toList();

        assertNull(lowered);
        assertFalse(continuationRan.get());
        assertTrue(diagnostics.hasErrors());
        assertEquals(1, typeCheckDiagnostics.size());
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("Bare 'return'"));
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("Object"));
    }

    @Test
    void lowerReservedSyntheticPropertyHelperMemberModuleStopsBeforeFurtherLoweringRuns() throws Exception {
        var module = parseModule(
                "lowering_blocked_reserved_helper_member.gd",
                """
                        class_name LoweringBlockedReservedHelperMember
                        extends RefCounted
                        
                        var _field_getter_value := 1
                        
                        func ping() -> int:
                            return 1
                        """
        );
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var continuationRan = new AtomicBoolean();
        var manager = new FrontendLoweringPassManager(List.of(
                new FrontendLoweringAnalysisPass(),
                _ -> continuationRan.set(true)
        ));

        var lowered = manager.lower(module, registry, diagnostics);
        var skeletonDiagnostics = diagnostics.snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.class_skeleton"))
                .toList();

        assertNull(lowered);
        assertFalse(continuationRan.get());
        assertTrue(diagnostics.hasErrors());
        assertEquals(1, skeletonDiagnostics.size());
        assertTrue(skeletonDiagnostics.getFirst().message().contains("_field_getter_"));
    }

    private static @NotNull FrontendModule parseModule(
            @NotNull String fileName,
            @NotNull String source
    ) {
        return parseModule(fileName, source, Map.of());
    }

    private static @NotNull FrontendModule parseModule(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        var parserService = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, parseDiagnostics);
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());
        return new FrontendModule("test_module", List.of(unit), topLevelCanonicalNameMap);
    }

    private record CompileBlockedCase(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull String expectedMessage
    ) {
    }
}

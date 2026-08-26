package gd.script.gdcc.frontend.lowering;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringAnalysisPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringClassSkeletonPass;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.lir.LirSignalDef;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendLoweringClassSkeletonPassTest {
    @Test
    void lowerEmitsModuleSkeletonFromCompileReadyFrontendModule() throws Exception {
        var prepared = prepareCompileReadyContext();
        var analysisPass = new FrontendLoweringAnalysisPass();
        var skeletonPass = new FrontendLoweringClassSkeletonPass();

        analysisPass.run(prepared.context());
        skeletonPass.run(prepared.context());

        var moduleSkeleton = prepared.context().requireAnalysisData().moduleSkeleton();
        var lirModule = prepared.context().lirModuleOrNull();

        assertNotNull(lirModule);
        assertEquals("test_module", lirModule.getModuleName());
        assertEquals(moduleSkeleton.moduleName(), lirModule.getModuleName());
        assertEquals(
                List.of("RuntimeOuter", "RuntimeOuter__sub__Inner", "PlainPeer"),
                lirModule.getClassDefs().stream().map(LirClassDef::getName).toList()
        );
        assertEquals(
                List.of("tmp/mapped_outer.gd", "tmp/mapped_outer.gd", "tmp/plain_peer.gd"),
                lirModule.getClassDefs().stream().map(LirClassDef::getSourceFile).toList()
        );
        assertSame(moduleSkeleton.allClassDefs().get(0), lirModule.getClassDefs().get(0));
        assertSame(moduleSkeleton.allClassDefs().get(1), lirModule.getClassDefs().get(1));
        assertSame(moduleSkeleton.allClassDefs().get(2), lirModule.getClassDefs().get(2));
        assertFalse(prepared.diagnostics().hasErrors());
    }

    @Test
    void lowerPreservesMappedTopLevelAndInnerCanonicalNames() throws Exception {
        var prepared = prepareCompileReadyContext();
        var analysisPass = new FrontendLoweringAnalysisPass();
        var skeletonPass = new FrontendLoweringClassSkeletonPass();

        analysisPass.run(prepared.context());
        skeletonPass.run(prepared.context());

        var lirModule = prepared.context().lirModuleOrNull();
        assertNotNull(lirModule);

        var mappedTopLevel = lirModule.getClassDefs().get(0);
        var inner = lirModule.getClassDefs().get(1);
        var plainPeer = lirModule.getClassDefs().get(2);

        assertEquals("RuntimeOuter", mappedTopLevel.getName());
        assertEquals("RefCounted", mappedTopLevel.getSuperName());
        assertEquals("RuntimeOuter__sub__Inner", inner.getName());
        assertEquals("RefCounted", inner.getSuperName());
        assertEquals("PlainPeer", plainPeer.getName());
        assertEquals("RefCounted", plainPeer.getSuperName());
    }

    @Test
    void lowerPreservesFunctionSkeletonsWithoutBasicBlocks() throws Exception {
        var prepared = prepareCompileReadyContext();
        var analysisPass = new FrontendLoweringAnalysisPass();
        var skeletonPass = new FrontendLoweringClassSkeletonPass();

        analysisPass.run(prepared.context());
        skeletonPass.run(prepared.context());

        var lirModule = prepared.context().lirModuleOrNull();
        assertNotNull(lirModule);

        var mappedTopLevel = lirModule.getClassDefs().get(0);
        var inner = lirModule.getClassDefs().get(1);
        var plainPeer = lirModule.getClassDefs().get(2);

        assertEquals(List.of("changed"), mappedTopLevel.getSignals().stream().map(LirSignalDef::getName).toList());
        assertEquals(List.of("count"), mappedTopLevel.getProperties().stream().map(LirPropertyDef::getName).toList());
        assertEquals(List.of("ping"), mappedTopLevel.getFunctions().stream().map(LirFunctionDef::getName).toList());
        assertEquals(List.of("nested_ready"), inner.getSignals().stream().map(LirSignalDef::getName).toList());
        assertEquals(List.of("label"), inner.getProperties().stream().map(LirPropertyDef::getName).toList());
        assertEquals(List.of("pong"), inner.getFunctions().stream().map(LirFunctionDef::getName).toList());
        assertEquals(List.of("noop"), plainPeer.getFunctions().stream().map(LirFunctionDef::getName).toList());

        for (var clazz : lirModule.getClassDefs()) {
            for (var function : clazz.getFunctions()) {
                assertEquals(0, function.getBasicBlockCount(), clazz.getName() + "::" + function.getName());
                assertTrue(function.getEntryBlockId().isEmpty(), clazz.getName() + "::" + function.getName());
            }
        }
    }

    @Test
    void lowerPropagatesCoroutineMarksOntoFunctionSkeletons() throws Exception {
        // Sema-only fixture: coroutine mark propagation is exercised through the shared
        // analyze(...) path. The pass consumes
        // `FrontendAnalysisData.coroutineFunctions` — an identity set over the same LirFunctionDef
        // shells the skeleton already publishes — so no name lookup is involved.
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var module = parseModule(
                List.of(new SourceFixture(
                        "coro_skeleton.gd",
                        """
                                class_name CoroSkeleton
                                extends Node
                                
                                signal pinged
                                
                                func _init():
                                    await pinged
                                
                                func inner():
                                    await pinged
                                
                                func run():
                                    await inner()
                                
                                func sync_helper() -> int:
                                    return 1
                                
                                class Inner:
                                    extends Node
                                
                                    signal inner_pinged
                                
                                    func watch():
                                        await inner_pinged
                                """
                )),
                Map.of()
        );
        var analysisData = new FrontendSemanticAnalyzer().analyze(module, classRegistry, diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected semantic errors: " + diagnostics.snapshot());
        var context = new FrontendLoweringContext(module, classRegistry, diagnostics);
        context.publishAnalysisData(analysisData);

        new FrontendLoweringClassSkeletonPass().run(context);

        var lirModule = context.requireLirModule();
        var topLevelFunctions = lirModule.getClassDefs().getFirst().getFunctions().stream()
                .collect(java.util.stream.Collectors.toMap(LirFunctionDef::getName, function -> function));
        var innerClassDef = lirModule.getClassDefs().stream()
                .filter(classDef -> classDef.getName().endsWith("__sub__Inner"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing inner class def"));
        assertAll(
                () -> assertTrue(topLevelFunctions.get("inner").isCoroutine()),
                () -> assertTrue(topLevelFunctions.get("run").isCoroutine(),
                        "awaiting a marked coroutine call marks the caller (fixed-point pass)"),
                () -> assertTrue(topLevelFunctions.get("_init").isCoroutine(),
                        "a constructor containing await follows the general marking rule"),
                () -> assertFalse(topLevelFunctions.get("sync_helper").isCoroutine(),
                        "unmarked functions keep the default false"),
                () -> assertTrue(
                        innerClassDef.getFunctions().stream()
                                .filter(function -> function.getName().equals("watch"))
                                .findFirst()
                                .orElseThrow()
                                .isCoroutine(),
                        "inner-class functions are covered via allClassDefs()"
                ),
                () -> assertSame(
                        analysisData.moduleSkeleton().allClassDefs().getFirst(),
                        lirModule.getClassDefs().getFirst(),
                        "the module publishes the same shells sema marked"
                )
        );
    }

    @Test
    void runFailsFastWhenAnalysisDataHasNotBeenPublishedYet() throws Exception {
        var context = new FrontendLoweringContext(
                new FrontendModule("test_module", List.of()),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                new DiagnosticManager()
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringClassSkeletonPass().run(context)
        );

        assertEquals("analysisData has not been published yet", exception.getMessage());
    }

    private static @NotNull PreparedContext prepareCompileReadyContext() throws Exception {
        var diagnostics = new DiagnosticManager();
        var context = new FrontendLoweringContext(
                parseModule(
                        List.of(
                                new SourceFixture(
                                        "mapped_outer.gd",
                                        """
                                                class_name MappedOuter
                                                extends RefCounted
                                                
                                                signal changed(value: int)
                                                var count: int
                                                
                                                func ping(value: int) -> int:
                                                    return value
                                                
                                                class Inner:
                                                    extends RefCounted
                                                
                                                    signal nested_ready()
                                                    var label: String
                                                
                                                    func pong() -> void:
                                                        pass
                                                """
                                ),
                                new SourceFixture(
                                        "plain_peer.gd",
                                        """
                                                class_name PlainPeer
                                                extends RefCounted
                                                
                                                func noop() -> void:
                                                    pass
                                                """
                                )
                        ),
                        Map.of("MappedOuter", "RuntimeOuter")
                ),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        return new PreparedContext(context, diagnostics);
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

    private record PreparedContext(
            @NotNull FrontendLoweringContext context,
            @NotNull DiagnosticManager diagnostics
    ) {
    }

    private record SourceFixture(
            @NotNull String fileName,
            @NotNull String source
    ) {
    }
}

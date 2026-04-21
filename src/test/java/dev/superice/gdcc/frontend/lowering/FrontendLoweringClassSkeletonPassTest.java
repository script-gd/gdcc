package dev.superice.gdcc.frontend.lowering;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringAnalysisPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringClassSkeletonPass;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

        assertEquals(List.of("changed"), mappedTopLevel.getSignals().stream().map(dev.superice.gdcc.lir.LirSignalDef::getName).toList());
        assertEquals(List.of("count"), mappedTopLevel.getProperties().stream().map(dev.superice.gdcc.lir.LirPropertyDef::getName).toList());
        assertEquals(List.of("ping"), mappedTopLevel.getFunctions().stream().map(dev.superice.gdcc.lir.LirFunctionDef::getName).toList());
        assertEquals(List.of("nested_ready"), inner.getSignals().stream().map(dev.superice.gdcc.lir.LirSignalDef::getName).toList());
        assertEquals(List.of("label"), inner.getProperties().stream().map(dev.superice.gdcc.lir.LirPropertyDef::getName).toList());
        assertEquals(List.of("pong"), inner.getFunctions().stream().map(dev.superice.gdcc.lir.LirFunctionDef::getName).toList());
        assertEquals(List.of("noop"), plainPeer.getFunctions().stream().map(dev.superice.gdcc.lir.LirFunctionDef::getName).toList());

        for (var clazz : lirModule.getClassDefs()) {
            for (var function : clazz.getFunctions()) {
                assertEquals(0, function.getBasicBlockCount(), clazz.getName() + "::" + function.getName());
                assertTrue(function.getEntryBlockId().isEmpty(), clazz.getName() + "::" + function.getName());
            }
        }
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

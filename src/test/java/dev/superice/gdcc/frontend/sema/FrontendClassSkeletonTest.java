package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnostic;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdIntType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrontendClassSkeletonTest {
    @Test
    void buildInjectsClassSkeletonsIntoRegistry() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();

        var baseSource = """
                class_name BaseClass
                extends RefCounted
                
                signal changed(value: int)
                var speed: float = 1.0
                
                func ping(x: int) -> int:
                    return x
                """;
        var childSource = """
                class_name ChildClass
                extends BaseClass
                
                var hp: int = 100
                
                func _ready():
                    pass
                """;
        var anonymousSource = """
                extends RefCounted
                
                var flag := true
                
                func tick():
                    pass
                """;

        var units = List.of(
                parserService.parseUnit(Path.of("tmp", "base_class.gd"), baseSource, diagnostics),
                parserService.parseUnit(Path.of("tmp", "child_class.gd"), childSource, diagnostics),
                parserService.parseUnit(Path.of("tmp", "no_name_script.gd"), anonymousSource, diagnostics)
        );

        var result = classSkeletonBuilder.build("test_module", units, registry, diagnostics, analysisData);
        assertEquals(3, result.classDefs().size());
        assertEquals(diagnostics.snapshot(), result.diagnostics());
        assertTrue(result.diagnostics().isEmpty());

        var childClass = findClassByName(result.classDefs(), "ChildClass");
        assertEquals("BaseClass", childClass.getSuperName());
        assertEquals(1, childClass.getProperties().size());
        assertEquals("hp", childClass.getProperties().getFirst().getName());
        assertEquals(1, childClass.getFunctions().size());
        assertEquals("_ready", childClass.getFunctions().getFirst().getName());

        var baseClass = findClassByName(result.classDefs(), "BaseClass");
        assertEquals("RefCounted", baseClass.getSuperName());
        assertEquals(1, baseClass.getSignals().size());
        var changedSignal = baseClass.getSignals().getFirst();
        assertEquals("changed", changedSignal.getName());
        assertEquals(1, changedSignal.getParameterCount());
        assertEquals("value", changedSignal.getParameter(0).getName());
        assertEquals(GdIntType.INT, changedSignal.getParameter(0).getType());

        var derivedNameClass = findClassByName(result.classDefs(), "NoNameScript");
        assertEquals("RefCounted", derivedNameClass.getSuperName());
        assertEquals(1, derivedNameClass.getProperties().size());
        assertEquals("flag", derivedNameClass.getProperties().getFirst().getName());

        assertNotNull(registry.findGdccClass("BaseClass"));
        assertNotNull(registry.findGdccClass("ChildClass"));
        assertNotNull(registry.findGdccClass("NoNameScript"));
    }

    /// Verifies the shared parse->skeleton pipeline keeps the original parse diagnostics exactly
    /// once, instead of silently re-importing the same `FrontendSourceUnit.parseDiagnostics()`
    /// when the builder already shares the same `DiagnosticManager`.
    @Test
    void buildKeepsSharedParseDiagnosticsWithoutDuplicatingThem() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();

        var unit = parserService.parseUnit(Path.of("tmp", "broken_shared_pipeline.gd"), """
                class_name BrokenSharedPipeline
                extends Node
                
                func _ready(
                    pass
                """, diagnostics);
        var parseSnapshot = diagnostics.snapshot();

        var result = classSkeletonBuilder.build("test_module", List.of(unit), registry, diagnostics, analysisData);
        var classDef = findClassByName(result.classDefs(), "BrokenSharedPipeline");

        assertEquals("BrokenSharedPipeline", classDef.getName());
        assertFalse(unit.parseDiagnostics().isEmpty());
        assertEquals(parseSnapshot.asList(), unit.parseDiagnostics());
        assertEquals(diagnostics.snapshot(), result.diagnostics());
        assertEquals(unit.parseDiagnostics(), result.diagnostics().asList());
        assertEquals(unit.parseDiagnostics().size(), result.diagnostics().size());
        assertTrue(result.diagnostics().asList().stream().allMatch(diagnostic -> diagnostic.category().equals("parse.lowering")));
    }

    @Test
    void buildDoesNotAutoImportPreexistingUnitParseDiagnostics() throws IOException {
        var parserService = new GdScriptParserService();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var analysisData = FrontendAnalysisData.bootstrap();

        var parsed = parserService.parseUnit(Path.of("tmp", "manual_parse_snapshot.gd"), """
                class_name ManualParseSnapshot
                extends Node
                
                func ping():
                    pass
                """, new DiagnosticManager());
        var manualUnit = new FrontendSourceUnit(
                parsed.path(),
                parsed.source(),
                parsed.ast(),
                List.of(FrontendDiagnostic.error(
                        "parse.lowering",
                        "manually attached parse diagnostic",
                        parsed.path(),
                        null
                ))
        );
        var diagnostics = new DiagnosticManager();

        var result = classSkeletonBuilder.build("test_module", List.of(manualUnit), registry, diagnostics, analysisData);

        assertEquals(1, result.classDefs().size());
        assertTrue(diagnostics.isEmpty());
        assertTrue(result.diagnostics().isEmpty());
    }

    private LirClassDef findClassByName(List<LirClassDef> classDefs, String className) {
        return classDefs.stream()
                .filter(classDef -> classDef.getName().equals(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + className));
    }
}

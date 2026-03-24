package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.scope.ClassRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrontendInheritanceCycleTest {
    @Test
    void buildReportsInheritanceCycleAndSkipsAffectedClasses() throws IOException {
        var parserService = new GdScriptParserService();
        var classSkeletonBuilder = new FrontendClassSkeletonBuilder();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var analysisData = FrontendAnalysisData.bootstrap();

        var sourceA = """
                class_name A
                extends B
                
                func f():
                    pass
                """;
        var sourceB = """
                class_name B
                extends A
                
                func g():
                    pass
                """;
        var sourceDependent = """
                class_name DependentOnCycle
                extends A
                
                func h():
                    pass
                """;
        var sourceStable = """
                class_name StableClass
                extends Node
                
                func ok():
                    pass
                """;

        var units = List.of(
                parserService.parseUnit(Path.of("tmp", "a.gd"), sourceA, diagnostics),
                parserService.parseUnit(Path.of("tmp", "b.gd"), sourceB, diagnostics),
                parserService.parseUnit(Path.of("tmp", "dependent.gd"), sourceDependent, diagnostics),
                parserService.parseUnit(Path.of("tmp", "stable.gd"), sourceStable, diagnostics)
        );

        var result = classSkeletonBuilder.build(
                new FrontendModule("cycle_module", units),
                registry,
                diagnostics,
                analysisData
        );

        assertEquals(diagnostics.snapshot(), result.diagnostics());
        assertFalse(result.diagnostics().isEmpty());
        assertEquals(List.of("StableClass"), result.sourceClassRelations().stream()
                .map(FrontendSourceClassRelation::topLevelClassDef)
                .map(LirClassDef::getName)
                .toList());
        assertEquals(1, result.sourceClassRelations().size());
        assertEquals(FrontendDiagnosticSeverity.ERROR, result.diagnostics().getFirst().severity());
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.inheritance_cycle")
        ));
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.message().contains("A")
                        && diagnostic.message().contains("B")
                        && diagnostic.message().contains("->")
        ));
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.class_skeleton")
                        && diagnostic.message().contains("DependentOnCycle")
                        && diagnostic.message().contains("super class 'A'")
        ));

        assertNull(registry.findGdccClass("A"));
        assertNull(registry.findGdccClass("B"));
        assertNull(registry.findGdccClass("DependentOnCycle"));
        assertNotNull(registry.findGdccClass("StableClass"));
    }
}

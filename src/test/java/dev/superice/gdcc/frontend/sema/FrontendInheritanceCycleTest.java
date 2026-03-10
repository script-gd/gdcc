package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.exception.FrontendSemanticException;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrontendInheritanceCycleTest {
    @Test
    void buildFailsFastWhenInheritanceCycleExists() throws IOException {
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

        var units = List.of(
                parserService.parseUnit(Path.of("tmp", "a.gd"), sourceA, diagnostics),
                parserService.parseUnit(Path.of("tmp", "b.gd"), sourceB, diagnostics)
        );

        var exception = assertThrows(
                FrontendSemanticException.class,
                () -> classSkeletonBuilder.build("cycle_module", units, registry, diagnostics, analysisData)
        );
        assertTrue(exception.getMessage().contains("A"));
        assertTrue(exception.getMessage().contains("B"));
        assertTrue(exception.getMessage().contains("->"));

        assertEquals(diagnostics.snapshot(), exception.diagnostics());
        assertFalse(exception.diagnostics().isEmpty());
        assertEquals(FrontendDiagnosticSeverity.ERROR, exception.diagnostics().getFirst().severity());
        assertTrue(exception.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.inheritance_cycle")
        ));
    }
}

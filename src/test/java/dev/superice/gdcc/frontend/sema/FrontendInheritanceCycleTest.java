package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.exception.FrontendSemanticException;
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
                parserService.parseUnit(Path.of("tmp", "a.gd"), sourceA),
                parserService.parseUnit(Path.of("tmp", "b.gd"), sourceB)
        );

        var exception = assertThrows(
                FrontendSemanticException.class,
                () -> classSkeletonBuilder.build("cycle_module", units, registry)
        );
        assertTrue(exception.getMessage().contains("A"));
        assertTrue(exception.getMessage().contains("B"));
        assertTrue(exception.getMessage().contains("->"));

        assertFalse(exception.diagnostics().isEmpty());
        assertEquals(FrontendDiagnosticSeverity.ERROR, exception.diagnostics().getFirst().severity());
        assertTrue(exception.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.inheritance_cycle")
        ));
    }
}

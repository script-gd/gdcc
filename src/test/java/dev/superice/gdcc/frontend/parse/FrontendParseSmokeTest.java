package dev.superice.gdcc.frontend.parse;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FrontendParseSmokeTest {
    @Test
    void parseUnitReturnsAstAndDiagnosticsWithoutCrash() {
        var source = """
                class_name Player
                extends Node
                
                func _ready():
                    print("ok")
                """;

        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var sourcePath = Path.of("tmp", "player.gd");
        var unit = parserService.parseUnit(sourcePath, source, diagnostics);

        assertNotNull(unit.ast());
        assertFalse(unit.ast().statements().isEmpty());
        assertNotNull(unit.parseDiagnostics());
        assertEquals(sourcePath, unit.path());
        assertEquals(unit.parseDiagnostics(), diagnostics.snapshot().asList());
    }

    @Test
    void parseUnitMapsMalformedScriptToFrontendErrorDiagnostic() {
        var source = """
                class_name Broken
                extends Node
                
                func _ready(
                    pass
                """;

        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var sourcePath = Path.of("tmp", "broken.gd");
        var unit = parserService.parseUnit(sourcePath, source, diagnostics);

        assertNotNull(unit.ast());
        assertFalse(unit.parseDiagnostics().isEmpty());
        assertEquals(unit.parseDiagnostics(), diagnostics.snapshot().asList());
        assertTrue(
                unit.parseDiagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.severity() == FrontendDiagnosticSeverity.ERROR)
        );

        var firstDiagnostic = unit.parseDiagnostics().getFirst();
        assertEquals(sourcePath, firstDiagnostic.sourcePath());
    }
}

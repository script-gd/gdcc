package dev.superice.gdcc.frontend.parse;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnostic;
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
        assertEquals(sourcePath, unit.path());
        assertTrue(diagnostics.snapshot().isEmpty());
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
        var snapshot = diagnostics.snapshot();

        assertNotNull(unit.ast());
        assertFalse(snapshot.isEmpty());
        assertTrue(
                snapshot.asList().stream()
                        .anyMatch(diagnostic -> diagnostic.severity() == FrontendDiagnosticSeverity.ERROR)
        );

        var firstDiagnostic = snapshot.getFirst();
        assertEquals(FrontendDiagnostic.sourcePathText(sourcePath), firstDiagnostic.sourcePath());
    }
}

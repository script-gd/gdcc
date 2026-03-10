package dev.superice.gdcc.frontend.parse;

import dev.superice.gdparser.frontend.lowering.CstToAstMapper;
import dev.superice.gdparser.infra.treesitter.GdParserFacade;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GdScriptParserServiceDiagnosticManagerTest {
    @Test
    void parseUnitKeepsManagerEmptyForWellFormedScriptWithoutLoweringDiagnostics() {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();

        var unit = parserService.parseUnit(Path.of("tmp", "ok.gd"), """
                class_name Player
                extends Node
                
                func _ready():
                    print("ok")
                """, diagnostics);

        assertTrue(unit.parseDiagnostics().isEmpty());
        assertTrue(diagnostics.isEmpty());
        assertFalse(diagnostics.hasErrors());
    }

    @Test
    void parseUnitMapsMalformedScriptToLoweringDiagnosticsAndMirrorsThemIntoManager() {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();

        var unit = parserService.parseUnit(Path.of("tmp", "broken.gd"), """
                class_name Broken
                extends Node
                
                func _ready(
                    pass
                """, diagnostics);

        assertFalse(unit.parseDiagnostics().isEmpty());
        assertEquals(unit.parseDiagnostics(), diagnostics.snapshot().asList());
        assertTrue(unit.parseDiagnostics().stream().allMatch(diagnostic -> diagnostic.category().equals("parse.lowering")));
        assertTrue(unit.parseDiagnostics().stream().anyMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
                        && diagnostic.message().startsWith("CST structural issue:")
                        && diagnostic.sourcePath().equals(Path.of("tmp", "broken.gd"))
                        && diagnostic.range() != null
        ));
    }

    @Test
    void parseUnitKeepsPerUnitSnapshotsStableWhenOneManagerParsesMultipleUnits() {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();

        var first = parserService.parseUnit(Path.of("tmp", "first_broken.gd"), """
                func _ready(
                    pass
                """, diagnostics);
        var firstSnapshot = first.parseDiagnostics();

        var second = parserService.parseUnit(Path.of("tmp", "second_broken.gd"), """
                class_name Broken
                func ping(
                    pass
                """, diagnostics);

        assertEquals(firstSnapshot, first.parseDiagnostics());
        assertFalse(firstSnapshot.isEmpty());
        assertFalse(second.parseDiagnostics().isEmpty());
        assertEquals(firstSnapshot.size() + second.parseDiagnostics().size(), diagnostics.snapshot().size());
    }

    @Test
    void parseUnitWrapsParserFacadeFailureAsInternalDiagnostic() throws Exception {
        var parserService = new GdScriptParserService(newBrokenParserFacade(), new CstToAstMapper());
        var diagnostics = new DiagnosticManager();

        var unit = parserService.parseUnit(Path.of("tmp", "internal_failure.gd"), "class_name Broken", diagnostics);

        assertEquals(1, unit.parseDiagnostics().size());
        assertEquals(unit.parseDiagnostics(), diagnostics.snapshot().asList());

        var diagnostic = unit.parseDiagnostics().getFirst();
        assertEquals(FrontendDiagnosticSeverity.ERROR, diagnostic.severity());
        assertEquals("parse.internal", diagnostic.category());
        assertTrue(diagnostic.message().startsWith("Unexpected parser failure:"));
        assertEquals(Path.of("tmp", "internal_failure.gd"), diagnostic.sourcePath());
        assertNull(diagnostic.range());

        assertNotNull(unit.ast());
        assertTrue(unit.ast().statements().isEmpty());
    }

    /// Constructs a facade with a null language through reflection so the test can
    /// exercise the runtime failure path without taking a compile-time dependency on
    /// the `org.treesitter.TSLanguage` type.
    private GdParserFacade newBrokenParserFacade() throws Exception {
        var languageType = Class.forName("org.treesitter.TSLanguage");
        var constructor = GdParserFacade.class.getConstructor(languageType);
        return constructor.newInstance(new Object[]{null});
    }
}

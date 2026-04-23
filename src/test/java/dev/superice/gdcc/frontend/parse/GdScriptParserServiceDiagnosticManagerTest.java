package dev.superice.gdcc.frontend.parse;

import dev.superice.gdparser.frontend.ast.UnknownStatement;
import dev.superice.gdparser.frontend.lowering.CstToAstMapper;
import dev.superice.gdparser.infra.treesitter.GdParserFacade;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnostic;
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

        assertNotNull(unit.ast());
        assertTrue(diagnostics.isEmpty());
        assertFalse(diagnostics.hasErrors());
    }

    @Test
    void parseUnitMapsMalformedScriptToLoweringDiagnosticsAndPublishesThemOnlyThroughManager() {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();

        var unit = parserService.parseUnit(Path.of("tmp", "broken.gd"), """
                class_name Broken
                extends Node
                
                func _ready(
                    pass
                """, diagnostics);

        var snapshot = diagnostics.snapshot();

        assertNotNull(unit.ast());
        assertFalse(snapshot.isEmpty());
        assertTrue(snapshot.asList().stream().allMatch(diagnostic -> diagnostic.category().equals("parse.lowering")));
        assertTrue(snapshot.asList().stream().anyMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
                        && diagnostic.message().startsWith("CST structural issue:")
                        && FrontendDiagnostic.sourcePathText(Path.of("tmp", "broken.gd")).equals(diagnostic.sourcePath())
                        && diagnostic.range() != null
        ));
    }

    @Test
    void parseUnitKeepsEarlierManagerSnapshotsStableWhenOneManagerParsesMultipleUnits() {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();

        var first = parserService.parseUnit(Path.of("tmp", "first_broken.gd"), """
                func _ready(
                    pass
                """, diagnostics);
        var firstSnapshot = diagnostics.snapshot();

        var second = parserService.parseUnit(Path.of("tmp", "second_broken.gd"), """
                class_name Broken
                func ping(
                    pass
                """, diagnostics);

        assertNotNull(first.ast());
        assertFalse(firstSnapshot.isEmpty());
        assertNotNull(second.ast());
        assertTrue(diagnostics.snapshot().size() > firstSnapshot.size());
    }

    @Test
    void parseUnitReportsRemovedImplicitConstructorBaseArgumentsSyntaxAsLoweringError() {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var sourcePath = Path.of("tmp", "legacy_constructor_header.gd");

        var unit = parserService.parseUnit(sourcePath, """
                class_name LegacyConstructorHeader
                extends Node
                
                func _init(seed).(seed):
                    pass
                """, diagnostics);
        var snapshot = diagnostics.snapshot();

        assertNotNull(unit.ast());
        assertTrue(diagnostics.hasErrors());
        assertFalse(snapshot.isEmpty());

        var legacyDiagnostic = snapshot.asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("parse.lowering"))
                .filter(diagnostic -> diagnostic.severity() == FrontendDiagnosticSeverity.ERROR)
                .filter(diagnostic -> diagnostic.message().contains("Implicit constructor base arguments"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected legacy constructor syntax diagnostic"));
        assertEquals(FrontendDiagnostic.sourcePathText(sourcePath), legacyDiagnostic.sourcePath());
        assertNotNull(legacyDiagnostic.range());

        var legacyStatement = unit.ast().statements().stream()
                .filter(UnknownStatement.class::isInstance)
                .map(UnknownStatement.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected rejected legacy constructor statement"));
        assertEquals("constructor_definition", legacyStatement.nodeType());
        assertTrue(legacyStatement.sourceText().contains("func _init(seed).(seed):"));
    }

    @Test
    void parseUnitWrapsParserFacadeFailureAsInternalDiagnostic() throws Exception {
        var parserService = new GdScriptParserService(newBrokenParserFacade(), new CstToAstMapper());
        var diagnostics = new DiagnosticManager();

        var unit = parserService.parseUnit(Path.of("tmp", "internal_failure.gd"), "class_name Broken", diagnostics);
        var snapshot = diagnostics.snapshot();

        assertEquals(1, snapshot.size());

        var diagnostic = snapshot.getFirst();
        assertEquals(FrontendDiagnosticSeverity.ERROR, diagnostic.severity());
        assertEquals("parse.internal", diagnostic.category());
        assertTrue(diagnostic.message().startsWith("Unexpected parser failure:"));
        assertEquals(FrontendDiagnostic.sourcePathText(Path.of("tmp", "internal_failure.gd")), diagnostic.sourcePath());
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

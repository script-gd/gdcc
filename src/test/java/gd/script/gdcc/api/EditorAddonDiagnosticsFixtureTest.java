package gd.script.gdcc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Behavioral anchors for the exact fixtures the Phase 3 engine test
/// (`EditorAddonScriptLanguageEngineTest` mode `gdcc_diag`) drives through the editor addon.
/// The addon maps `analyze.run` diagnostics into editor errors/warnings keyed by
/// `displayPath`; these tests pin what the frontend actually reports for each fixture
/// (category, severity, 1-based position, message shape) so the engine-side assertions test
/// the addon's mapping instead of guessing frontend output.
class EditorAddonDiagnosticsFixtureTest {

    /// `extends "res://..."` is legal GDScript (the GDScript LSP stays silent) but rejected by
    /// the gdcc frontend — the canonical Phase 3 "gdcc-only diagnostic" fixture.
    static final String PATH_EXTENDS_SOURCE = """
            class_name DiagPathChild
            extends "res://diag_base.gd"
            """;

    /// `@onready var camera = $Camera3D` is the GDScript-idiomatic form the LSP itself
    /// recommends (so the LSP stays silent), while the gdcc compile-only gate still blocks
    /// the deferred get-node expression. The gate runs exclusively under
    /// `includeLowering=true`; the deferred expression also produces a WARNING, so this
    /// fixture exercises both the errors[] and the warnings[] mapping.
    static final String LOWERING_SOURCE = """
            class_name DiagLowering
            extends Node

            @onready var camera = $Camera3D
            """;

    static final String PATH_EXTENDS_DISPLAY = "res://diag_path_child.gd3";
    static final String LOWERING_DISPLAY = "res://diag_lowering.gd3";

    private static AnalysisResult analyzeSingle(String displayPath, String source, boolean includeLowering) {
        var api = ApiCompileTestSupport.newApi(ApiCompileTestSupport.RecordingCompiler.succeeding());
        api.createModule("fixtures", "Phase 3 Fixtures");
        api.putFile("fixtures", "/src/" + displayPath.substring("res://".length()), source, displayPath);
        return api.analyze("fixtures", new AnalyzeOptions(includeLowering));
    }

    private static List<FrontendDiagnostic> diagnosticsFor(AnalysisResult result, String displayPath) {
        return result.diagnostics().asList().stream()
                .filter(diagnostic -> displayPath.equals(diagnostic.sourcePath()))
                .toList();
    }

    private static FrontendDiagnostic singleDiagnostic(
            AnalysisResult result, String displayPath, FrontendDiagnosticSeverity severity, String category) {
        List<FrontendDiagnostic> matches = diagnosticsFor(result, displayPath).stream()
                .filter(diagnostic -> diagnostic.severity() == severity)
                .filter(diagnostic -> diagnostic.category().equals(category))
                .toList();
        assertEquals(1, matches.size(),
                "expected exactly one " + severity + "/" + category + " for " + displayPath);
        var diagnostic = matches.getFirst();
        assertNotNull(diagnostic.range(), "editor mapping needs a concrete range");
        return diagnostic;
    }

    @Test
    void pathExtendsFixtureProducesClassSkeletonErrorAtHeader() {
        var result = analyzeSingle(PATH_EXTENDS_DISPLAY, PATH_EXTENDS_SOURCE, true);

        assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome());
        var diagnostic = singleDiagnostic(
                result, PATH_EXTENDS_DISPLAY, FrontendDiagnosticSeverity.ERROR, "sema.class_skeleton");
        assertTrue(diagnostic.message().contains("DiagPathChild"), diagnostic.message());
        assertTrue(diagnostic.message().contains("path-based extends"), diagnostic.message());
        // The rejection anchors to the class header (the `class_name` line), not the
        // `extends` line — the engine test pins this 1-based position.
        assertEquals(1, diagnostic.range().start().line());
        // No other diagnostic may reference this fixture (single-error display contract).
        assertEquals(1, diagnosticsFor(result, PATH_EXTENDS_DISPLAY).size());
    }

    @Test
    void loweringFixtureProducesWarningPlusCompileCheckError() {
        var result = analyzeSingle(LOWERING_DISPLAY, LOWERING_SOURCE, true);

        assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome());
        // Exactly two diagnostics on the same anchor (line 4, column 23): the
        // deferred-resolution WARNING and the compile-only ERROR.
        assertEquals(2, diagnosticsFor(result, LOWERING_DISPLAY).size());
        var error = singleDiagnostic(
                result, LOWERING_DISPLAY, FrontendDiagnosticSeverity.ERROR, "sema.compile_check");
        assertTrue(error.message().contains("Get-node expression"), error.message());
        assertEquals(4, error.range().start().line());
        assertEquals(23, error.range().start().column());
        var warning = singleDiagnostic(result, LOWERING_DISPLAY,
                FrontendDiagnosticSeverity.WARNING, "sema.deferred_expression_resolution");
        assertEquals(4, warning.range().start().line());
        assertEquals(error.range(), warning.range());
        assertEquals(AnalysisResult.LoweringStatus.FAILED, result.loweringStatus());

        // Without includeLowering the compile-only gate never runs: the error must be absent.
        var withoutLowering = analyzeSingle(LOWERING_DISPLAY, LOWERING_SOURCE, false);
        assertTrue(diagnosticsFor(withoutLowering, LOWERING_DISPLAY).stream()
                .noneMatch(entry -> entry.category().equals("sema.compile_check")));
    }

    @Test
    void mixedModuleReportsBothFixturesInOneRound() {
        // The engine test keeps all fixtures in the single diagnostics module: pin that one
        // `analyze.run` round reports the skeleton error AND the lowering diagnostics
        // together, each under its own displayPath.
        var api = ApiCompileTestSupport.newApi(ApiCompileTestSupport.RecordingCompiler.succeeding());
        api.createModule("mixed", "Mixed Fixtures");
        api.putFile("mixed", "/src/diag_path_child.gd3", PATH_EXTENDS_SOURCE, PATH_EXTENDS_DISPLAY);
        api.putFile("mixed", "/src/diag_lowering.gd3", LOWERING_SOURCE, LOWERING_DISPLAY);

        var result = api.analyze("mixed", new AnalyzeOptions(true));

        assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome());
        singleDiagnostic(result, PATH_EXTENDS_DISPLAY, FrontendDiagnosticSeverity.ERROR, "sema.class_skeleton");
        singleDiagnostic(result, LOWERING_DISPLAY, FrontendDiagnosticSeverity.ERROR, "sema.compile_check");
        singleDiagnostic(result, LOWERING_DISPLAY,
                FrontendDiagnosticSeverity.WARNING, "sema.deferred_expression_resolution");
        assertEquals(AnalysisResult.LoweringStatus.FAILED, result.loweringStatus());
    }

    @Test
    void displayPathKeysSameBasenameIndependently() {
        var api = ApiCompileTestSupport.newApi(ApiCompileTestSupport.RecordingCompiler.succeeding());
        api.createModule("dup", "Duplicate Basenames");
        // Same basename under different VFS paths and different display paths: diagnostics
        // must key by displayPath, never by basename or VFS path.
        api.putFile("dup", "/src/a/same.gd3", PATH_EXTENDS_SOURCE.replace("DiagPathChild", "DupA"),
                "res://a/same.gd3");
        api.putFile("dup", "/src/b/same.gd3", PATH_EXTENDS_SOURCE.replace("DiagPathChild", "DupB"),
                "res://b/same.gd3");

        var result = api.analyze("dup", new AnalyzeOptions(true));

        var a = singleDiagnostic(result, "res://a/same.gd3", FrontendDiagnosticSeverity.ERROR, "sema.class_skeleton");
        var b = singleDiagnostic(result, "res://b/same.gd3", FrontendDiagnosticSeverity.ERROR, "sema.class_skeleton");
        assertTrue(a.message().contains("DupA"), a.message());
        assertTrue(b.message().contains("DupB"), b.message());
        assertTrue(result.sourcePaths().contains("res://a/same.gd3"));
        assertTrue(result.sourcePaths().contains("res://b/same.gd3"));
    }
}

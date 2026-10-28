package gd.script.gdcc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/// Behavioral anchors for the exact fixtures the gdcc diagnostics channel engine test
/// (`EditorAddonScriptLanguageEngineTest` mode `gdcc_diag`) drives through the editor addon.
/// The addon maps `analyze.run` diagnostics into editor errors/warnings keyed by
/// `displayPath`; these tests pin what the frontend actually reports for each fixture
/// (category, severity, 1-based position, message shape) so the engine-side assertions test
/// the addon's mapping instead of guessing frontend output.
@Tag("editor_addon")
class EditorAddonDiagnosticsFixtureTest {

    /// `extends "res://..."` is legal GDScript (the GDScript LSP stays silent) but rejected by
    /// the gdcc frontend — the canonical "gdcc-only diagnostic" fixture.
    static final String PATH_EXTENDS_SOURCE = """
            class_name DiagPathChild
            extends "res://diag_base.gd"
            """;

    /// An explicitly typed plain get-node initializer (`var camera: Node = $Camera3D`)
    /// stays a deferred expression under gdcc, so the compile-only gate still blocks it
    /// while the code is valid GDScript. The `@warning_ignore_start`/`@warning_ignore_restore`
    /// region pair keeps Godot's own analyzer silent on the non-`@onready` get-node default;
    /// gdcc ignores region annotations entirely, so the fixture isolates the gdcc
    /// diagnostics. The gate runs exclusively under `includeLowering=true`; the deferred
    /// expression also produces a WARNING, so this fixture exercises both the errors[]
    /// and the warnings[] mapping.
    static final String LOWERING_SOURCE = """
            class_name DiagLowering
            extends Node

            @warning_ignore_start("get_node_default_without_onready")
            var camera: Node = $Camera3D
            @warning_ignore_restore("get_node_default_without_onready")
            """;

    /// The explicitly typed `@onready` form is the supported counterpart: the same
    /// get-node initializer lowers cleanly, so the positive case must stay silent.
    static final String ONREADY_TYPED_SOURCE = """
            class_name DiagOnreadyTyped
            extends Node

            @onready var camera: Node = $Camera3D
            """;

    static final String PATH_EXTENDS_DISPLAY = "res://diag_path_child.gd3";
    static final String LOWERING_DISPLAY = "res://diag_lowering.gd3";
    static final String ONREADY_TYPED_DISPLAY = "res://diag_onready_typed.gd3";

    private static AnalysisResult analyzeSingle(String displayPath, String source, boolean includeLowering) {
        var api = ApiCompileTestSupport.newApi(ApiCompileTestSupport.RecordingCompiler.succeeding());
        api.createModule("fixtures", "Diagnostics Fixtures");
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
        // Exactly two diagnostics on the same anchor (line 5, column 20): the
        // deferred-resolution WARNING and the compile-only ERROR.
        assertEquals(2, diagnosticsFor(result, LOWERING_DISPLAY).size());
        var error = singleDiagnostic(
                result, LOWERING_DISPLAY, FrontendDiagnosticSeverity.ERROR, "sema.compile_check");
        assertTrue(error.message().contains("Get-node expression"), error.message());
        assertEquals(5, error.range().start().line());
        assertEquals(20, error.range().start().column());
        var warning = singleDiagnostic(result, LOWERING_DISPLAY,
                FrontendDiagnosticSeverity.WARNING, "sema.deferred_expression_resolution");
        assertEquals(5, warning.range().start().line());
        assertEquals(error.range(), warning.range());
        assertEquals(AnalysisResult.LoweringStatus.FAILED, result.loweringStatus());

        // Without includeLowering the compile-only gate never runs: the error must be absent.
        var withoutLowering = analyzeSingle(LOWERING_DISPLAY, LOWERING_SOURCE, false);
        assertTrue(diagnosticsFor(withoutLowering, LOWERING_DISPLAY).stream()
                .noneMatch(entry -> entry.category().equals("sema.compile_check")));
    }

    @Test
    void typedOnreadyInitializerLowersCleanly() {
        // Positive counterpart of the lowering fixture: the same get-node initializer
        // under an explicitly typed `@onready` is supported end to end, so no deferred
        // warning and no compile-check error may surface.
        var result = analyzeSingle(ONREADY_TYPED_DISPLAY, ONREADY_TYPED_SOURCE, true);

        assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome());
        assertEquals(AnalysisResult.LoweringStatus.SUCCEEDED, result.loweringStatus());
        var diagnostics = diagnosticsFor(result, ONREADY_TYPED_DISPLAY);
        assertTrue(diagnostics.isEmpty(),
                () -> "typed @onready get-node must not produce diagnostics: " + diagnostics);
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

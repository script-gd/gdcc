package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.c.gen.CCodegen;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.FrontendLoweringPassManager;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// HR-9 scenario 10b: a NON-editor Godot process (`is_editor_hint() == false`, the same value an
/// editor-launched F5 game process observes — plan appendix A.3) must take the
/// `DIRECT_NON_RELOAD` path of the §5.8 three-mode machine: lambdas, standalone Callables and
/// method-name connections behave exactly as in pre-HRX releases. The behavioral mode anchor is
/// the standalone Callable `to_string`: the direct path keeps gdcc's custom `GDCC.<kind>(...)`
/// rendering, while the HRX thunk path always renders `<CallableCustom>` (asserted in the editor
/// by `GodotEditorHotReloadIntegrationTest.lambdaConnectionRebindsToNewImplementationAfterReload`).
/// DIRECT mode builds no hub and allocates no executable heap by construction; that structural
/// half is anchored by `GdccHrxRuntimeSmokeTest.directModeShouldKeepTheLegacyDispatchIntact`.
/// The headed F5 variant stays on the manual checklist (headless and F5 game processes share the
/// same `is_editor_hint` value, so the mode decision is identical).
///
/// Environment-aware: skips through JUnit assumptions when Zig or `GODOT_BIN` is missing.
public class GodotRuntimeDirectPathIntegrationTest {
    private static final Path BUILD_DIR = Path.of("tmp/test/runtime_direct_path/build").toAbsolutePath();

    @Test
    void directModeKeepsLegacyCallableBehaviorInNonEditorProcess() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping runtime direct-path integration test");
            return;
        }
        Files.createDirectories(BUILD_DIR);
        var buildResult = buildModule(
                "hr_runtime_direct",
                List.of(new SourceFileSpec(
                        Path.of("tmp/test/runtime_direct_path/src/hr_direct.gd").toAbsolutePath(),
                        hrDirectSource()
                )),
                Map.of("HrDirect", "RuntimeHrDirect"),
                BUILD_DIR
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "HrDirectNode",
                        "RuntimeHrDirect",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(directPathTestScript())
        ));
        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(combinedOutput.contains("HR10B_PASS"), () -> "Direct-path assertions did not pass.\nOutput:\n" + combinedOutput);
        assertFalse(combinedOutput.contains("HR10B_FAIL"), () -> "Direct-path assertion failed.\nOutput:\n" + combinedOutput);
    }

    /// Fixture exercising all three Callable flavors (lambda, standalone utility, method-name)
    /// on one signal plus a driver-facing lambda factory and the mode-discriminating
    /// `utility_to_string`.
    private static @NotNull String hrDirectSource() {
        return """
                class_name HrDirect
                extends Node

                signal ping(value: int)

                var hits: int = 0
                var last: int = -1

                func arm() -> void:
                    ping.connect(func(value: int) -> void:
                        hits += 1
                        last = value * 2
                    )
                    ping.connect(_on_ping)

                func fire(value: int) -> void:
                    ping.emit(value)

                func _on_ping(value: int) -> void:
                    hits += 10

                func make_lambda() -> Callable:
                    return func(value: int) -> int:
                        return value + 7

                func utility_to_string() -> String:
                    var cb = lerp
                    return str(cb)
                """;
    }

    /// Interpreted validation script (attached by the runner). `HR10B_PASS` is the success
    /// marker; every failed check reports one `HR10B_FAIL` line via push_error.
    private static @NotNull String directPathTestScript() {
        return """
                extends Node

                func _ready() -> void:
                    var target = get_parent().get_node_or_null("HrDirectNode")
                    if target == null:
                        push_error("HR10B_FAIL: fixture node missing")
                        return
                    target.call("arm")
                    target.call("fire", 21)
                    if target.get("hits") != 11 or target.get("last") != 42:
                        push_error("HR10B_FAIL: lambda/method-name connections misbehaved: hits="
                                + str(target.get("hits")) + " last=" + str(target.get("last")))
                        return
                    var cb = target.call("make_lambda")
                    if cb == null or not cb.is_valid() or cb.call(1) != 8:
                        push_error("HR10B_FAIL: lambda Callable broken in non-editor process")
                        return
                    var util_str = str(target.call("utility_to_string"))
                    if not util_str.begins_with("GDCC."):
                        push_error("HR10B_FAIL: direct-path to_string anchor broken (HRX engaged?): "
                                + util_str)
                        return
                    print("HR10B_PASS")
                """;
    }

    /// Same frontend -> C codegen -> Zig pipeline as the editor hot-reload tests, minus the
    /// reload orchestration (scenario 10b is single-generation).
    private static @NotNull CBuildResult buildModule(
            @NotNull String moduleName,
            @NotNull List<SourceFileSpec> sources,
            @NotNull Map<String, String> topLevelCanonicalNameMap,
            @NotNull Path buildDir
    ) throws IOException {
        var parser = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var units = sources.stream()
                .map(source -> parser.parseUnit(source.sourcePath(), source.source(), parseDiagnostics))
                .toList();
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());
        var module = new FrontendModule(moduleName, units, topLevelCanonicalNameMap);

        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);
        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        Files.createDirectories(buildDir);
        var projectInfo = new CProjectInfo(
                moduleName,
                GodotVersion.V451,
                buildDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), lowered);
        var buildResult = new CProjectBuilder().buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), () -> "Native build should succeed. Build log:\n" + buildResult.buildLog());
        return buildResult;
    }

    private record SourceFileSpec(@NotNull Path sourcePath, @NotNull String source) {
    }
}

package gd.script.gdcc.rpc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gd.script.gdcc.api.API;
import gd.script.gdcc.api.AnalysisResult;
import gd.script.gdcc.api.AnalyzeOptions;
import gd.script.gdcc.api.CompileOptions;
import gd.script.gdcc.api.CompileResult;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.GodotGdextensionTestRunner;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import gd.script.gdcc.backend.c.build.ZigUtil;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Phase 0 gate of the .gd3 editor integration plan (`doc/module_impl/editor_addon/
/// gd3_editor_integration_implementation.md` §5/§7): three probe levels over the sources in
/// `src/test/resources/editor_addon_probe/` (kept outside the addon directory so they never
/// ship in the plugin). P0-A proves gdcc can analyze+lower the required engine API surface
/// (ScriptLanguageExtension / ScriptExtension / ResourceFormatLoader / ResourceFormatSaver
/// overrides, registration, networking, file, text, time and editor-only type routes);
/// P0-B (zig-gated) proves the same module survives C code generation and linking; P0-C
/// (zig + `GODOT_BIN` gated) loads the compiled probe extension in a real Godot process and
/// drives language registration plus virtual dispatch through an interpreted SceneTree script.
class EditorAddonIntegrationProbeTest {
    private static final Path PROBE_SOURCE_DIR = Path.of("src/test/resources/editor_addon_probe");
    private static final String MODULE_ID = "gdcc_editor_probe";
    private static final Path CASE_ROOT = Path.of("tmp/test/editor_addon_probe/default");
    private static final String EXTENSION_FILE_NAME = "gdcc_editor_probe.gdextension";
    private static final String RESULT_MARKER = "GD3_PROBE_RESULT: ";
    private static final long COMPILE_TIMEOUT_MINUTES = 5;
    /// Java-side timeout bounds the Godot process; the driver itself always quits on the first
    /// failed step, so a healthy run finishes in seconds.
    private static final long PROCESS_TIMEOUT_MINUTES = 5;

    /// Driver steps in execution order; the summary must contain exactly these, all `ok`.
    private static final List<String> EXPECTED_STEPS = List.of(
            "class_exists", "instantiate", "register",
            "retrieve_and_call", "script_roundtrip", "typed_array_virtuals", "unregister");

    /// Interpreted driver script (gdcc feature limits do not apply to it). Asserts the probe
    /// classes registered through ClassDB, registers ProbeLang as a script language, calls the
    /// overridden virtuals through their bound `_...` methods, and round-trips a ProbeScript
    /// created by the language. On the first failed step it records the failure and jumps to
    /// `_finish`, because later steps depend on earlier ones.
    private static final String DRIVER_SCRIPT = """
            extends SceneTree

            var _steps: Array = []

            func _initialize() -> void:
                _run()

            func _step(step: String, ok: bool, detail: String = "") -> void:
                _steps.append({"step": step, "ok": ok, "detail": detail})

            func _finish() -> void:
                print("GD3_PROBE_RESULT: " + JSON.stringify({"steps": _steps}))
                quit()

            func _run() -> void:
                var all_exist: bool = true
                for class_name_text in ["ProbeLang", "ProbeScript", "ProbeLoader", "ProbeSaver", "ProbeEngineCalls"]:
                    if not ClassDB.class_exists(class_name_text):
                        all_exist = false
                _step("class_exists", all_exist)
                if not all_exist:
                    _finish()
                    return

                var lang: Object = ClassDB.instantiate("ProbeLang")
                _step("instantiate", lang != null)
                if lang == null:
                    _finish()
                    return

                var register_err: int = Engine.register_script_language(lang)
                _step("register", register_err == OK, "err=" + str(register_err))
                if register_err != OK:
                    _finish()
                    return

                var found: ScriptLanguage = null
                for i in range(Engine.get_script_language_count()):
                    var candidate: ScriptLanguage = Engine.get_script_language(i)
                    if candidate == lang:
                        found = candidate
                var name_text: String = ""
                var ext_text: String = ""
                var recognized: PackedStringArray = PackedStringArray()
                if found != null:
                    name_text = found._get_name()
                    ext_text = found._get_extension()
                    recognized = found._get_recognized_extensions()
                var retrieve_ok: bool = found != null and name_text == "GD3Probe" \\
                        and ext_text == "gd3probe" \\
                        and recognized.size() == 1 and recognized[0] == "gd3probe"
                _step("retrieve_and_call", retrieve_ok,
                        "name=" + name_text + " ext=" + ext_text + " recognized=" + str(recognized))
                if not retrieve_ok:
                    _finish()
                    return

                var script: Object = lang._create_script()
                var bound_script: Object = lang.make_script()
                var check_code: int = lang.check_instantiation()
                var direct_script: Object = ClassDB.instantiate("ProbeScript")
                var failed_sub: String = ""
                if check_code != 0:
                    failed_sub = "check:" + str(check_code)
                if direct_script == null:
                    failed_sub = failed_sub + " direct_instantiate_null"
                if script == null:
                    failed_sub = failed_sub + " create_script_null"
                if bound_script == null:
                    failed_sub = failed_sub + " make_script_null"
                if failed_sub == "":
                    if script._can_instantiate() != false:
                        failed_sub = "can_instantiate"
                if failed_sub == "":
                    script._set_source_code("extends RefCounted\\n")
                    if script._get_source_code() != "extends RefCounted\\n":
                        failed_sub = "source_roundtrip:" + script._get_source_code()
                if failed_sub == "" and script._get_language() != lang:
                    failed_sub = "language_identity"
                if failed_sub == "" and script._is_tool() != false:
                    failed_sub = "is_tool"
                if failed_sub == "" and script._get_instance_base_type() != &"RefCounted":
                    failed_sub = "base_type:" + str(script._get_instance_base_type())
                _step("script_roundtrip", failed_sub == "", failed_sub)

                # Typed-array returning virtuals through the live dispatch chain (§2.3 probe
                # conclusion): all overrides return empty typed arrays.
                var typed_ok: bool = script != null
                var typed_detail: String = ""
                if script == null:
                    typed_detail = "no script"
                else:
                    var pub_funcs: Variant = found._get_public_functions()
                    var templates: Variant = found._get_built_in_templates(&"Object")
                    var docs: Variant = script._get_documentation()
                    var members: Variant = script._get_members()
                    typed_ok = pub_funcs is Array and pub_funcs.is_empty()
                    typed_ok = typed_ok and templates is Array and templates.is_empty()
                    typed_ok = typed_ok and docs is Array and docs.is_empty()
                    typed_ok = typed_ok and members is Array and members.is_empty()
                    typed_detail = str(typeof(pub_funcs)) + "/" + str(typeof(templates)) \\
                            + "/" + str(typeof(docs)) + "/" + str(typeof(members))
                _step("typed_array_virtuals", typed_ok, typed_detail)

                var unregister_err: int = Engine.unregister_script_language(lang)
                _step("unregister", unregister_err == OK, "err=" + str(unregister_err))

                lang.free()
                _finish()
            """;

    @Test
    void probeSourcesAnalyzeAndLowerCleanly() throws IOException {
        var api = new API();
        api.createModule(MODULE_ID, "Editor Addon Probe");
        var probeFiles = listProbeFiles();
        for (var file : probeFiles) {
            // `.gd3` virtual paths are collected directly as analyze sources; the module keeps
            // every probe class in one unit so cross-class references resolve.
            api.putFile(MODULE_ID, "/src/" + file.getFileName().toString(), Files.readString(file));
        }

        var result = api.analyze(MODULE_ID, new AnalyzeOptions(true));

        assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome(), () -> diagnosticsText(result));
        assertFalse(result.hasErrors(), () -> diagnosticsText(result));
        assertEquals(AnalysisResult.LoweringStatus.SUCCEEDED, result.loweringStatus(), () -> diagnosticsText(result));
        var expectedPaths = probeFiles.stream()
                .map(file -> "/src/" + file.getFileName().toString())
                .toList();
        assertEquals(expectedPaths, result.sourcePaths());
    }

    @Test
    void probeModuleCompilesNatively() throws IOException {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping editor addon probe native compile");
            return;
        }

        var compileResult = compileProbeModule(CASE_ROOT.resolve("probe-build"));
        assertEquals(CompileResult.Outcome.SUCCESS, compileResult.outcome(),
                () -> "probe module native build failed: " + compileResult.failureMessage()
                        + "\nbuild log:\n" + compileResult.buildLog());
        // Auxiliary artifacts (e.g. PDB files on Windows) may legally accompany the library;
        // only the loadable dynamic library count is contractual.
        var loadableLibraries = compileResult.artifacts().stream()
                .filter(artifact -> EditorAddonProjectInstaller.isDynamicLibrary(artifact.getFileName().toString()))
                .count();
        assertEquals(1, loadableLibraries,
                () -> "expected exactly one loadable dynamic library, got " + compileResult.artifacts());
    }

    @Test
    void probeExtensionRegistersInRealGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping editor addon probe engine test");
            return;
        }
        var godotBinary = GodotGdextensionTestRunner.findGodotBinaryFromEnv();
        if (godotBinary == null) {
            Assumptions.abort("GODOT_BIN not found; skipping editor addon probe engine test");
            return;
        }

        // 1) Compile the probe module natively through the public API.
        var compileResult = compileProbeModule(CASE_ROOT.resolve("probe-build"));
        assertEquals(CompileResult.Outcome.SUCCESS, compileResult.outcome(),
                () -> "probe module native build failed: " + compileResult.failureMessage()
                        + "\nbuild log:\n" + compileResult.buildLog());

        // 2) Minimal project (no addon project copy needed: the probe extension stands alone)
        //    plus the compiled library installed as a loadable GDExtension.
        var projectDir = CASE_ROOT.resolve("project");
        recreateProjectDir(projectDir);
        Files.writeString(projectDir.resolve("project.godot"), """
                config_version=5

                [application]
                config/name="GdccEditorProbe"
                """);
        EditorAddonProjectInstaller.installExtension(
                projectDir, compileResult.artifacts(), EXTENSION_FILE_NAME,
                COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());
        Files.writeString(projectDir.resolve("probe_driver.gd"), DRIVER_SCRIPT);

        var output = runGodotDriver(godotBinary, projectDir);
        var summary = extractSummary(output);
        assertDriverStepsOk(summary, output);
    }

    /// Compiles the probe sources into a native GDExtension library under `projectPath`.
    /// Mirrors `EditorAddonProjectInstaller.compileClientLibrary` but targets the probe module
    /// (multiple sources under `src/test/resources/editor_addon_probe/`, separate module id so
    /// the artifact basename never collides with the real addon library).
    private static CompileResult compileProbeModule(Path projectPath) throws IOException {
        try (var api = new API()) {
            api.createModule(MODULE_ID, "Editor Addon Probe");
            api.setCompileOptions(MODULE_ID, new CompileOptions(
                    GodotVersion.V451, projectPath.toAbsolutePath(),
                    COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform(),
                    false, CompileOptions.DEFAULT_OUTPUT_MOUNT_ROOT));
            for (var file : listProbeFiles()) {
                api.putFile(MODULE_ID, "/src/" + file.getFileName().toString(), Files.readString(file));
            }
            var taskId = api.compile(MODULE_ID);
            var deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(COMPILE_TIMEOUT_MINUTES);
            while (System.nanoTime() < deadline) {
                var snapshot = api.getCompileTask(taskId);
                if (snapshot.completed()) {
                    return java.util.Objects.requireNonNull(snapshot.result());
                }
                try {
                    //noinspection BusyWait
                    Thread.sleep(250);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while waiting for the probe module build");
                }
            }
            throw new AssertionError("Probe module build did not complete within the deadline");
        }
    }

    /// Probe sources sorted by file name so VFS paths and `sourcePaths` assertions are stable.
    private static List<Path> listProbeFiles() throws IOException {
        try (Stream<Path> stream = Files.list(PROBE_SOURCE_DIR)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".gd3"))
                    .sorted()
                    .toList();
        }
    }

    /// Deletes any previous project copy and recreates the directory, so stale extension
    /// artifacts or driver output can never leak between runs.
    private static void recreateProjectDir(Path projectDir) throws IOException {
        if (Files.exists(projectDir)) {
            try (var walk = Files.walk(projectDir)) {
                for (var path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        Files.createDirectories(projectDir);
    }

    /// Runs `godot --headless --path <project> -s probe_driver.gd` and returns the combined
    /// output. `-s` script mode never loads `main.tscn`, so completion is the process exit
    /// triggered by the driver's own `quit()`, bounded by the process timeout (destroyed
    /// forcibly past it).
    private static String runGodotDriver(Path godotBinary, Path projectDir)
            throws IOException, InterruptedException {
        var command = List.of(
                godotBinary.toString(),
                "--headless",
                "--path", projectDir.toAbsolutePath().toString(),
                "-s", projectDir.resolve("probe_driver.gd").toAbsolutePath().toString());
        var process = new ProcessBuilder(command).directory(projectDir.toFile()).start();
        var stdout = new StringBuffer();
        var stderr = new StringBuffer();
        var stdoutReader = startLineReader(process.getInputStream(), stdout);
        var stderrReader = startLineReader(process.getErrorStream(), stderr);
        try {
            if (!process.waitFor(PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                // Give the killed process a moment to die, then collect what the readers have —
                // a timeout diagnosis is useless without the fullest possible output.
                process.waitFor(5, TimeUnit.SECONDS);
                stdoutReader.join(5_000);
                stderrReader.join(5_000);
                throw new AssertionError("Godot driver timed out; output so far:\n"
                        + stdout + "\n--- stderr ---\n" + stderr);
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw exception;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        // The process has exited; join the readers briefly so failure messages see full output.
        stdoutReader.join(5_000);
        stderrReader.join(5_000);
        var output = stdout + "\n--- stderr ---\n" + stderr;
        // A clean driver run ends through `quit()` (exit code 0); a crash during GDExtension
        // unload after the marker line must fail the test, not pass as a false green.
        if (process.exitValue() != 0) {
            throw new AssertionError("Godot driver exited with code " + process.exitValue()
                    + "; output:\n" + output);
        }
        return output;
    }

    private static Thread startLineReader(InputStream stream, StringBuffer output) {
        return Thread.ofVirtual().name("gdcc-editor-probe-driver-", 0).start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (IOException exception) {
                output.append("[stream read failed: ").append(exception.getMessage()).append("]\n");
            }
        });
    }

    /// Extracts the summary JSON from the driver's marker line. Godot's startup banner and
    /// engine noise share stdout, so matching is line-oriented (never whole-buffer) and the
    /// JSON is parsed from the marker offset to the end of that line.
    private static JsonObject extractSummary(String output) {
        for (var line : output.split("\n")) {
            var markerIndex = line.indexOf(RESULT_MARKER);
            if (markerIndex >= 0) {
                return JsonParser.parseString(line.substring(markerIndex + RESULT_MARKER.length()).trim())
                        .getAsJsonObject();
            }
        }
        throw new AssertionError(RESULT_MARKER.trim() + " marker not found in Godot output:\n" + output);
    }

    private static void assertDriverStepsOk(JsonObject summary, String output) {
        var steps = summary.getAsJsonArray("steps");
        var actualNames = new java.util.ArrayList<String>();
        for (var step : steps) {
            actualNames.add(step.getAsJsonObject().get("step").getAsString());
        }
        assertEquals(EXPECTED_STEPS, actualNames,
                () -> "driver stopped early or reordered steps; output:\n" + output);
        for (var step : steps) {
            var stepObject = step.getAsJsonObject();
            assertTrue(stepObject.get("ok").getAsBoolean(),
                    () -> "driver step failed: " + stepObject + "\nfull output:\n" + output);
        }
    }

    private static String diagnosticsText(AnalysisResult result) {
        var text = new StringBuilder("Analysis diagnostics:\n");
        for (FrontendDiagnostic diagnostic : result.diagnostics().asList()) {
            text.append(diagnostic.severity())
                    .append(' ')
                    .append(diagnostic.category())
                    .append(' ')
                    .append(diagnostic.message())
                    .append('\n');
        }
        return text.toString();
    }
}

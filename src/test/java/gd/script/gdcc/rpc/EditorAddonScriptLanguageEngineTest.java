package gd.script.gdcc.rpc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gd.script.gdcc.api.CompileResult;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.GodotGdextensionTestRunner;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import gd.script.gdcc.backend.c.build.ZigUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Phase 1 engine acceptance of the .gd3 editor integration (plan §7/§8.2), gated on zig +
/// `GODOT_BIN`. Unlike the bootstrap/probe tests (`-s` script mode), these cases run a real
/// headless EDITOR (`--headless --editor`), because the feature under test is editor
/// integration: the addon project copy is launched with both the real `gdcc` plugin and an
/// injected test-only driver plugin (`addons/gdcc_test_driver`, interpreted GDScript, never
/// shipped) enabled; the driver asserts and prints one `GD3_TEST_RESULT: ` summary line.
///
/// Cases:
/// - `language`: language registered as GD3; ResourceLoader load round-trip (source, language
///   identity, header parse); ResourceSaver round-trip; `_reload` from disk; worker-thread
///   `load_threaded_*` path (the §2.4 purity constraint); `_validate` answers valid; a
///   disable/enable cycle keeps the resident language instance identical.
/// - `launch`: cold start without a service auto-spawns `gdcc serve` via the configured
///   launch command (placeholders substituted by the launcher); disabling the plugin then
///   gracefully stops exactly that owned process (port closes).
/// - `launch_bad`: a launch command pointing at a nonexistent executable reports the error
///   and never crashes the editor (negative path).
/// - `launch_none`: no launch command and no service keeps the historical passive behavior —
///   connection fails, nothing is spawned (negative path).
///
/// Editor settings are user-global, so every case redirects the child process's config dirs
/// (`APPDATA` / `XDG_CONFIG_HOME` / `HOME`) into the case directory — the test never pollutes
/// the developer's real editor settings.
class EditorAddonScriptLanguageEngineTest {
    private static final Path ADDON_PROJECT_DIR = Path.of("src/editor_addon");
    private static final Path CASE_ROOT = Path.of("tmp/test/editor_addon_script_language");
    private static final String RESULT_MARKER = "GD3_TEST_RESULT: ";
    /// Pure fallback so a broken driver still terminates the editor reasonably fast; healthy
    /// cases quit through the driver long before this frame count.
    private static final int QUIT_AFTER_FRAMES = 30000;
    private static final long PROCESS_TIMEOUT_MINUTES = 10;

    private static final Map<String, List<String>> EXPECTED_STEPS = Map.of(
            "language", List.of(
                    "config", "language_registered", "language_identity", "load_roundtrip",
                    "save_roundtrip", "save_bad_path", "reload_from_disk", "threaded_load",
                    "validate_valid_true", "create_script_valid", "header_scan_edges",
                    "disabled_safe", "reenabled_same_instance"),
            "launch", List.of(
                    "config", "configure", "relaunched", "server_up", "server_stopped_on_disable"),
            "launch_bad", List.of("config", "configure", "relaunched", "no_server_started", "editor_alive"),
            "launch_none", List.of("config", "relaunched", "passive_no_server", "editor_alive"),
            // Reproduction of the manual-testing crash: the Create Resource dialog instantiates
            // a ClassDB GdccScript (no language setup) and pushes it to the inspector/editor.
            "create_resource", List.of(
                    "config", "classdb_instantiate", "property_list", "save", "edit_resource",
                    "disabled_create_safe", "survived")
    );

    /// Interpreted driver plugin (gdcc feature limits do not apply to it). Every mode records
    /// ordered steps; the Java side requires the exact expected sequence, all `ok`, so an
    /// early bail-out fails loudly instead of silently skipping assertions.
    private static final String DRIVER_PLUGIN = """
            @tool
            extends EditorPlugin

            var _steps: Array = []
            var _config: Dictionary = {}

            func _enter_tree() -> void:
                _run()

            func _step(step: String, ok: bool, detail: String = "") -> void:
                _steps.append({"step": step, "ok": ok, "detail": detail})
                print("GD3_TEST_STEP: " + step + " ok=" + str(ok) + (" " + detail if detail != "" else ""))

            func _finish() -> void:
                print("GD3_TEST_RESULT: " + JSON.stringify({"steps": _steps}))
                get_tree().quit()

            func _write_text_file(path: String, content: String) -> void:
                var f := FileAccess.open(path, FileAccess.WRITE)
                f.store_string(content)
                f.close()

            func _find_gd3_language() -> ScriptLanguage:
                var i := 0
                while i < Engine.get_script_language_count():
                    var candidate: ScriptLanguage = Engine.get_script_language(i)
                    # `_get_name` is bound only on ScriptLanguageExtension (the GDVIRTUAL glue),
                    # not on the base ScriptLanguage — guard before calling or the loop dies on
                    # the engine's own GDScript language entry.
                    if candidate != null and candidate is ScriptLanguageExtension \\
                            and candidate._get_name() == "GD3":
                        return candidate
                    i += 1
                return null

            func _port_open(host: String, port: int) -> bool:
                var peer := StreamPeerTCP.new()
                if peer.connect_to_host(host, port) != OK:
                    return false
                var open := false
                var deadline := Time.get_ticks_msec() + 1500
                while Time.get_ticks_msec() < deadline:
                    peer.poll()
                    var status := peer.get_status()
                    if status == StreamPeerTCP.STATUS_CONNECTED:
                        open = true
                        break
                    if status != StreamPeerTCP.STATUS_CONNECTING:
                        break
                    await get_tree().process_frame
                peer.disconnect_from_host()
                return open

            # Disabling the gdcc plugin runs its `_exit_tree`, whose last step shuts down a
            # spawned server; used by the launch mode so every exit path stops what it started.
            func _ensure_gdcc_disabled() -> void:
                if EditorInterface.is_plugin_enabled("gdcc"):
                    EditorInterface.set_plugin_enabled("gdcc", false)
                    await get_tree().process_frame
                    await get_tree().process_frame

            func _run() -> void:
                # Let the editor settle; the gdcc plugin's `_enter_tree` already ran (plugin
                # load order follows the enabled list, gdcc first).
                await get_tree().process_frame
                await get_tree().process_frame
                var config_file := FileAccess.open("res://gd3_test_config.json", FileAccess.READ)
                if config_file == null:
                    _step("config", false, "missing gd3_test_config.json")
                    _finish()
                    return
                var parsed: Variant = JSON.parse_string(config_file.get_as_text())
                if typeof(parsed) != TYPE_DICTIONARY:
                    _step("config", false, "config is not a JSON object")
                    _finish()
                    return
                _config = parsed
                _step("config", true)
                var mode := str(_config.get("mode", ""))
                if mode == "language":
                    await _run_language_mode()
                elif mode == "launch":
                    await _run_launch_mode()
                elif mode == "launch_bad":
                    await _run_launch_bad_mode()
                elif mode == "launch_none":
                    await _run_launch_none_mode()
                elif mode == "create_resource":
                    await _run_create_resource_mode()
                else:
                    _step("mode", false, "unknown mode " + mode)
                _finish()

            func _run_language_mode() -> void:
                var lang := _find_gd3_language()
                _step("language_registered", lang != null)
                if lang == null:
                    return
                var identity_ok: bool = lang._get_extension() == "gd3" and lang._get_type() == "GdccScript"
                var recognized: PackedStringArray = lang._get_recognized_extensions()
                identity_ok = identity_ok and recognized.size() == 1 and recognized[0] == "gd3"
                _step("language_identity", identity_ok, str(recognized))

                var sample_path := "res://gd3_phase1_sample.gd3"
                var source := "@tool\\nclass_name Gd3Phase1Sample\\nextends RefCounted\\n"
                _write_text_file(sample_path, source)
                var res: Resource = ResourceLoader.load(sample_path)
                var load_ok: bool = res != null
                if load_ok:
                    load_ok = load_ok and res._get_language() == lang
                    load_ok = load_ok and res._get_source_code() == source
                    load_ok = load_ok and res._is_valid()
                    load_ok = load_ok and res._get_global_name() == &"Gd3Phase1Sample"
                    load_ok = load_ok and res._get_instance_base_type() == &"RefCounted"
                    load_ok = load_ok and not res._can_instantiate()
                _step("load_roundtrip", load_ok)
                if not load_ok:
                    return

                var updated := source + "\\n# edited in memory\\n"
                res._set_source_code(updated)
                var save_err := ResourceSaver.save(res, sample_path, 0)
                var save_ok: bool = save_err == OK and FileAccess.get_file_as_string(sample_path) == updated
                _step("save_roundtrip", save_ok, "err=" + str(save_err))

                # Negative: saving into a nonexistent directory must surface an error, never a
                # false OK (the saver reports write/open failures through its Error return).
                var bad_save_err: int = ResourceSaver.save(res, "res://no_such_dir/deep/probe.gd3", 0)
                _step("save_bad_path", bad_save_err != OK, "err=" + str(bad_save_err))

                var on_disk := source + "\\n# external edit\\n"
                _write_text_file(sample_path, on_disk)
                var reload_err: int = res._reload(false)
                _step("reload_from_disk", reload_err == OK and res._get_source_code() == on_disk,
                        "err=" + str(reload_err))

                # Worker-thread load path: `_load` must be a pure load (no service/cache/Node
                # access), otherwise this races or deadlocks.
                var threaded_path := "res://gd3_phase1_threaded.gd3"
                _write_text_file(threaded_path, source)
                var threaded_ok := ResourceLoader.load_threaded_request(threaded_path, "", false, 1) == OK
                if threaded_ok:
                    var deadline := Time.get_ticks_msec() + 30000
                    var status := ResourceLoader.load_threaded_get_status(threaded_path, [])
                    while status == ResourceLoader.THREAD_LOAD_IN_PROGRESS and Time.get_ticks_msec() < deadline:
                        await get_tree().process_frame
                        status = ResourceLoader.load_threaded_get_status(threaded_path, [])
                    threaded_ok = status == ResourceLoader.THREAD_LOAD_LOADED
                    if threaded_ok:
                        var threaded_res: Resource = ResourceLoader.load_threaded_get(threaded_path)
                        threaded_ok = threaded_res != null and threaded_res._get_source_code() == source
                _step("threaded_load", threaded_ok)

                var validation: Dictionary = lang._validate("extends RefCounted\\n", sample_path, true, true, true, true)
                _step("validate_valid_true", validation.get("valid", false) == true, str(validation))

                # The editor's create-then-edit path: a fresh script carrying source is valid.
                var fresh: Variant = lang._create_script()
                var create_ok: bool = fresh != null
                if create_ok:
                    fresh._set_source_code("extends RefCounted\\n")
                    create_ok = fresh._is_valid() \\
                            and fresh._get_source_code() == "extends RefCounted\\n" \\
                            and not fresh._can_instantiate() \\
                            and fresh._get_language() == lang
                _step("create_script_valid", create_ok)

                # Header scan edges: tab separators and an indented (inner-scope / invalid)
                # `extends` line must not perturb the top-level header.
                var edge_path := "res://gd3_phase1_edges.gd3"
                _write_text_file(edge_path, "@tool\\nclass_name\\tGd3Phase1Edges\\nextends\\tRefCounted\\n    extends Node\\n")
                var edge_res: Resource = ResourceLoader.load(edge_path, "", 0)
                var edge_ok: bool = edge_res != null \\
                        and edge_res._get_global_name() == &"Gd3Phase1Edges" \\
                        and edge_res._get_instance_base_type() == &"RefCounted"
                if edge_res != null:
                    DirAccess.remove_absolute(ProjectSettings.globalize_path(edge_path))
                # Same-line `class_name X extends Y` with mixed tab/space separators.
                var mixed_path := "res://gd3_phase1_mixed.gd3"
                _write_text_file(mixed_path, "@tool\\nclass_name\\tGd3Phase1Mixed\\textends Node\\n")
                var mixed_res: Resource = ResourceLoader.load(mixed_path, "", 0)
                edge_ok = edge_ok and mixed_res != null \\
                        and mixed_res._get_global_name() == &"Gd3Phase1Mixed" \\
                        and mixed_res._get_instance_base_type() == &"Node"
                if mixed_res != null:
                    DirAccess.remove_absolute(ProjectSettings.globalize_path(mixed_path))
                _step("header_scan_edges", edge_ok)
                # Disable with the loaded script alive: the language unregisters, but the
                # resident instance must stay valid and keep answering `_validate`.
                EditorInterface.set_plugin_enabled("gdcc", false)
                await get_tree().process_frame
                await get_tree().process_frame
                var gone := _find_gd3_language() == null
                var still_valid: bool = res._get_language() == lang \\
                        and lang._validate("extends RefCounted\\n", sample_path, true, true, true, true).get("valid", false) == true
                _step("disabled_safe", gone and still_valid)
                EditorInterface.set_plugin_enabled("gdcc", true)
                await get_tree().process_frame
                await get_tree().process_frame
                var relang := _find_gd3_language()
                _step("reenabled_same_instance", relang != null and relang == lang)

                DirAccess.remove_absolute(ProjectSettings.globalize_path(sample_path))
                DirAccess.remove_absolute(ProjectSettings.globalize_path(threaded_path))

            func _run_launch_mode() -> void:
                var port := int(_config["port"])
                # The dock's endpoint drives which port the launcher targets; steering it to
                # the test's free port keeps the case hermetic (a real service on the default
                # port would otherwise be adopted as an external server and break the spawn
                # assertions).
                var settings := EditorInterface.get_editor_settings()
                settings.set_setting("gdcc/server/host", "127.0.0.1")
                settings.set_setting("gdcc/server/port", port)
                settings.set_setting("gdcc/server/launch_command", str(_config["launch_command"]))
                _step("configure", true)
                # Re-enable so the plugin startup path runs with the command configured; the
                # dock's auto-setup consults the launcher, which spawns the service.
                EditorInterface.set_plugin_enabled("gdcc", false)
                await get_tree().process_frame
                await get_tree().process_frame
                EditorInterface.set_plugin_enabled("gdcc", true)
                _step("relaunched", true)
                var client := GdccRpcClient.new()
                add_child(client)
                client.host = "127.0.0.1"
                client.port = port
                var up := false
                var deadline := Time.get_ticks_msec() + 45000
                while Time.get_ticks_msec() < deadline and not up:
                    var pong: Dictionary = await client.ping().completed
                    up = pong.get("ok", false) and str(pong.get("result", "")) == "pong"
                    if not up:
                        await get_tree().create_timer(0.5).timeout
                _step("server_up", up)
                if not up:
                    await _ensure_gdcc_disabled()
                    return
                EditorInterface.set_plugin_enabled("gdcc", false)
                await get_tree().process_frame
                var down := false
                var down_deadline := Time.get_ticks_msec() + 45000
                while Time.get_ticks_msec() < down_deadline and not down:
                    down = not await _port_open("127.0.0.1", port)
                    if not down:
                        await get_tree().create_timer(0.5).timeout
                _step("server_stopped_on_disable", down)
                client.queue_free()

            func _run_launch_bad_mode() -> void:
                var port := int(_config["port"])
                var bad_settings := EditorInterface.get_editor_settings()
                bad_settings.set_setting("gdcc/server/host", "127.0.0.1")
                bad_settings.set_setting("gdcc/server/port", port)
                bad_settings.set_setting("gdcc/server/launch_command", str(_config["launch_command"]))
                _step("configure", true)
                EditorInterface.set_plugin_enabled("gdcc", false)
                await get_tree().process_frame
                await get_tree().process_frame
                EditorInterface.set_plugin_enabled("gdcc", true)
                _step("relaunched", true)
                # Give the launcher a moment to attempt the spawn and report the failure; the
                # launcher's own log line is asserted from the Java side.
                await get_tree().create_timer(3.0).timeout
                var open := await _port_open("127.0.0.1", port)
                _step("no_server_started", not open)
                _step("editor_alive", true)

            func _run_launch_none_mode() -> void:
                var port := int(_config["port"])
                var none_settings := EditorInterface.get_editor_settings()
                none_settings.set_setting("gdcc/server/host", "127.0.0.1")
                none_settings.set_setting("gdcc/server/port", port)
                none_settings.set_setting("gdcc/server/launch_command", "")
                EditorInterface.set_plugin_enabled("gdcc", false)
                await get_tree().process_frame
                await get_tree().process_frame
                EditorInterface.set_plugin_enabled("gdcc", true)
                _step("relaunched", true)
                await get_tree().create_timer(2.0).timeout
                var open := await _port_open("127.0.0.1", port)
                _step("passive_no_server", not open)
                _step("editor_alive", true)

            # Mimics the Create Resource dialog path (create_dialog.cpp ->
            # InspectorDock::_resource_created): ClassDB instantiation of the script class
            # WITHOUT the language factory (so `setup()` never ran and `_language` is null),
            # object property enumeration, a save, and pushing the resource to the editor.
            func _run_create_resource_mode() -> void:
                var res: Resource = ClassDB.instantiate("GdccScript") as Resource
                _step("classdb_instantiate", res != null)
                if res == null:
                    return
                # EditorData::instantiate_object_properties enumerates the property list.
                var props: Array = res.get_property_list()
                _step("property_list", props.size() > 0, "props=" + str(props.size()))
                var probe_path := "res://gd3_create_dialog_probe.gd3"
                var save_err: int = ResourceSaver.save(res, probe_path, 0)
                _step("save", save_err == OK, "err=" + str(save_err))
                # InspectorDock pushes the new resource into the editor (inspector + script
                # editor paths); the debugger-active editor also queries the language early.
                EditorInterface.edit_resource(res)
                await get_tree().process_frame
                await get_tree().process_frame
                _step("edit_resource", true)
                DirAccess.remove_absolute(ProjectSettings.globalize_path(probe_path))
                # Disabled state: the language is unregistered, but the resident instance must
                # still answer `_get_language` so editor paths never see null.
                EditorInterface.set_plugin_enabled("gdcc", false)
                await get_tree().process_frame
                await get_tree().process_frame
                var res2: Resource = ClassDB.instantiate("GdccScript") as Resource
                var lang_ok := false
                if res2 != null:
                    EditorInterface.edit_resource(res2)
                    await get_tree().process_frame
                    await get_tree().process_frame
                    var lang2: ScriptLanguage = res2._get_language()
                    lang_ok = lang2 != null and lang2._get_name() == "GD3"
                _step("disabled_create_safe", lang_ok)
                EditorInterface.set_plugin_enabled("gdcc", true)
                await get_tree().process_frame
                await get_tree().process_frame
                _step("survived", true)
            """;

    private static final String DRIVER_MANIFEST = """
            [plugin]

            name="GDCC Test Driver"
            description="Test-only driver for the gdcc editor integration engine tests."
            author="gdcc"
            version="0.0.0"
            script="driver_plugin.gd"
            """;

    /// Compiled once per JVM: every case installs the same native addon library.
    private static CompileResult compiledAddon;

    @Test
    void languageSkeletonWorksInRealEditor() throws Exception {
        runCase("language", new JsonObject());
    }

    @Test
    void launcherSpawnsAndStopsOwnedServer() throws Exception {
        var port = findFreePort();
        var javaBin = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                        ? "java.exe" : "java");
        // The launcher substitutes {host}/{port}; double quotes keep spaced paths tokenizable
        // (the launcher tokenizes on whitespace with double-quote grouping).
        var launchCommand = "\"" + javaBin + "\" -cp \""
                + System.getProperty("java.class.path")
                + "\" gd.script.gdcc.Main serve --host {host} --port {port}";
        var config = new JsonObject();
        config.addProperty("port", port);
        config.addProperty("launch_command", launchCommand);
        runCase("launch", config);
    }

    @Test
    void launcherReportsMissingExecutableWithoutCrashing() throws Exception {
        var config = new JsonObject();
        config.addProperty("port", findFreePort());
        config.addProperty("launch_command", "gdcc-definitely-missing-binary-xyz serve --port {port}");
        var output = runCase("launch_bad", config);
        assertTrue(output.contains("GDCC server launcher") && output.contains("OS.create_process failed"),
                () -> "expected the launcher's spawn-failure report in the editor output:\n" + output);
    }

    @Test
    void launcherWithoutCommandKeepsPassiveFailure() throws Exception {
        var config = new JsonObject();
        config.addProperty("port", findFreePort());
        runCase("launch_none", config);
    }

    /// Manual-testing regression: creating a GdccScript through the editor's Create Resource
    /// dialog crashed the editor (signal 11). The dialog ClassDB-instantiates the class
    /// directly (no language factory, so no `setup()`), enumerates the property list, saves,
    /// and pushes it into the editor. This case replays that sequence and requires the editor
    /// to survive.
    @Test
    void createResourceDialogMimicDoesNotCrash() throws Exception {
        runCase("create_resource", new JsonObject());
    }

    private static String runCase(String caseName, JsonObject config) throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping script language engine test");
            return "";
        }
        var godotBinary = GodotGdextensionTestRunner.findGodotBinaryFromEnv();
        if (godotBinary == null) {
            Assumptions.abort("GODOT_BIN not found; skipping script language engine test");
            return "";
        }
        if (!EXPECTED_STEPS.containsKey(caseName)) {
            throw new IllegalArgumentException("Unknown case: " + caseName);
        }

        var compileResult = compileAddonOnce();
        assertEquals(CompileResult.Outcome.SUCCESS, compileResult.outcome(),
                () -> "addon module native build failed: " + compileResult.failureMessage()
                        + "\nbuild log:\n" + compileResult.buildLog());

        var caseDir = CASE_ROOT.resolve(caseName);
        var projectDir = caseDir.resolve("project");
        EditorAddonProjectInstaller.copyProject(ADDON_PROJECT_DIR, projectDir);
        // Install over the in-place path so the copy contains exactly one .gdextension (the
        // committed-source one is stale); the editor's own scan and the written extension list
        // agree on it.
        EditorAddonProjectInstaller.installExtension(
                projectDir, EditorAddonProjectInstaller.EXTENSION_SUB_DIR, compileResult.artifacts(),
                EditorAddonProjectInstaller.EXTENSION_FILE_NAME,
                COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform(), true);

        // Test-only driver plugin (never part of the shipped addon).
        var driverDir = projectDir.resolve("addons/gdcc_test_driver");
        Files.createDirectories(driverDir);
        Files.writeString(driverDir.resolve("plugin.cfg"), DRIVER_MANIFEST);
        Files.writeString(driverDir.resolve("driver_plugin.gd"), DRIVER_PLUGIN);
        config.addProperty("mode", caseName);
        Files.writeString(projectDir.resolve("gd3_test_config.json"), config.toString());
        patchEnabledPlugins(projectDir);

        var output = runEditor(godotBinary, projectDir, caseDir);
        var summary = extractSummary(output);
        assertDriverStepsOk(caseName, summary, output);
        return output;
    }

    private static synchronized CompileResult compileAddonOnce() throws IOException {
        if (compiledAddon == null) {
            compiledAddon = EditorAddonProjectInstaller.compileClientLibrary(
                    CASE_ROOT.resolve("addon-build"), TargetPlatform.getNativePlatform());
        }
        return compiledAddon;
    }

    /// Adds the driver plugin to the enabled list (gdcc stays first so load order matches real
    /// usage); fails fast when the anchor drifts.
    private static void patchEnabledPlugins(Path projectDir) throws IOException {
        var projectFile = projectDir.resolve("project.godot");
        var content = Files.readString(projectFile);
        var anchor = "enabled=PackedStringArray(\"res://addons/gdcc/plugin.cfg\")";
        if (!content.contains(anchor)) {
            throw new IllegalStateException("project.godot enabled-plugins anchor not found:\n" + content);
        }
        Files.writeString(projectFile, content.replace(anchor,
                "enabled=PackedStringArray(\"res://addons/gdcc/plugin.cfg\", "
                        + "\"res://addons/gdcc_test_driver/plugin.cfg\")"));
    }

    /// Runs `godot --headless --editor --path <copy> --quit-after <N>` and returns the combined
    /// output. Config directories are redirected into the case dir so the editor's settings
    /// writes (use_thread, the launch command, layout state) never touch the real user profile.
    private static String runEditor(Path godotBinary, Path projectDir, Path caseDir)
            throws IOException, InterruptedException {
        var isolatedConfig = Files.createDirectories(caseDir.resolve("config-home"));
        var command = List.of(
                godotBinary.toString(),
                "--headless",
                "--editor",
                "--path", projectDir.toAbsolutePath().toString(),
                "--quit-after", String.valueOf(QUIT_AFTER_FRAMES));
        var processBuilder = new ProcessBuilder(command).directory(projectDir.toFile());
        var environment = processBuilder.environment();
        environment.put("APPDATA", isolatedConfig.toAbsolutePath().toString());
        environment.put("XDG_CONFIG_HOME", isolatedConfig.toAbsolutePath().toString());
        environment.put("HOME", isolatedConfig.toAbsolutePath().toString());
        var process = processBuilder.start();
        var stdout = new StringBuffer();
        var stderr = new StringBuffer();
        var stdoutReader = startLineReader(process.getInputStream(), stdout);
        var stderrReader = startLineReader(process.getErrorStream(), stderr);
        try {
            if (!process.waitFor(PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                stdoutReader.join(5_000);
                stderrReader.join(5_000);
                throw new AssertionError("Editor driver timed out; output so far:\n"
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
        stdoutReader.join(5_000);
        stderrReader.join(5_000);
        var output = stdout + "\n--- stderr ---\n" + stderr;
        if (process.exitValue() != 0) {
            throw new AssertionError("Editor driver exited with code " + process.exitValue()
                    + "; output:\n" + output);
        }
        return output;
    }

    private static Thread startLineReader(InputStream stream, StringBuffer output) {
        return Thread.ofVirtual().name("gdcc-editor-language-driver-", 0).start(() -> {
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

    private static JsonObject extractSummary(String output) {
        for (var line : output.split("\n")) {
            var markerIndex = line.indexOf(RESULT_MARKER);
            if (markerIndex >= 0) {
                return JsonParser.parseString(line.substring(markerIndex + RESULT_MARKER.length()).trim())
                        .getAsJsonObject();
            }
        }
        throw new AssertionError(RESULT_MARKER.trim() + " marker not found in editor output:\n" + output);
    }

    private static void assertDriverStepsOk(String caseName, JsonObject summary, String output) {
        var steps = summary.getAsJsonArray("steps");
        var actualNames = new java.util.ArrayList<String>();
        for (var step : steps) {
            actualNames.add(step.getAsJsonObject().get("step").getAsString());
        }
        assertEquals(EXPECTED_STEPS.get(caseName), actualNames,
                () -> "driver stopped early or reordered steps; output:\n" + output);
        for (var step : steps) {
            var stepObject = step.getAsJsonObject();
            assertTrue(stepObject.get("ok").getAsBoolean(),
                    () -> "driver step failed: " + stepObject + "\nfull output:\n" + output);
        }
    }

    private static int findFreePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

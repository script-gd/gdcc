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
import java.util.ArrayList;
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
                    "disabled_create_safe", "survived"),
            // Phase 2: LSP connection lifecycle (backoff retry across the editor-boot window
            // where the GDScript language server does not listen yet, then drop/reconnect
            // cycles — endpoint steering forces a disconnect, and a plugin disable/enable
            // cycle exercises stop/start re-handshake. Note: the server's runtime restart on
            // `use_thread` changes is driven by NOTIFICATION_EDITOR_SETTINGS_CHANGED, which
            // is emitted only by C++ UI flows and is not reachable from script, so an actual
            // server-side restart cannot be triggered by the driver.
            "lsp_connect", List.of(
                    "config", "lsp_endpoint", "lsp_ready", "reconnect_drop",
                    "reconnect_attempts", "reconnect_recover", "plugin_cycle_reconnect",
                    "blocking_safe_after_cycle", "thread_reprobe_clears_flag", "survived"),
            // Phase 2: `_validate` diagnostic merge — degraded path, error/clean/warning
            // samples with exact line anchoring, non-BMP column accounting (R11), and the
            // uninstalled-service safe answer.
            "lsp_validate", List.of(
                    "config", "lsp_endpoint_closed", "validate_degraded", "lsp_endpoint",
                    "lsp_ready", "validate_error", "validate_clean", "validate_warning",
                    "validate_cjk_columns", "disabled_validate_safe", "survived"),
            // Phase 2: publishDiagnostics carry no version, so attribution must follow the
            // per-URI generation FIFO (g1 sent, g2 sent, then g1's notification must still
            // bind to g1); plus a blocking request/response round-trip.
            "lsp_fifo", List.of(
                    "config", "lsp_endpoint", "lsp_ready", "fifo_open_change",
                    "fifo_g1_diagnostics", "fifo_g2_diagnostics", "fifo_timeout_realign",
                    "blocking_request", "survived"),
            // Phase 2 (review finding): runtime enable flips `use_thread` too late — the
            // server already started main-thread-polled and a programmatic set_setting
            // cannot restart it (§2.6). The session must refuse inline waits (no 150ms
            // starvation per validate) while diagnostics still surface asynchronously.
            "lsp_runtime_enable", List.of(
                    "config", "lsp_port_set", "editor_ready", "plugin_enabled", "lsp_ready",
                    "blocking_refused", "async_diagnostics_surface", "survived"),
            // Phase 2 (review round 3): boot-time prime followed by a disable BEFORE the
            // server listened must un-prime (the restored setting makes the server start
            // main-thread-polled); the next enable re-probes and degrades correctly.
            "lsp_disable_before_prime", List.of(
                    "config", "lsp_port_set", "disabled_before_listen", "editor_ready",
                    "plugin_reenabled", "lsp_ready", "blocking_refused",
                    "async_diagnostics_surface", "survived")
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

            func _service() -> Node:
                return get_tree().root.get_node_or_null("GdccEditorService")

            # Points the editor's GDScript language server AND our client at the test's free
            # port. Runs before the editor finishes booting, so the server (which starts
            # listening only after editor-ready) picks up the port; the service's
            # repeat-install contract writes the endpoint back into the LSP client.
            func _configure_lsp_port(port: int) -> bool:
                var settings := EditorInterface.get_editor_settings()
                settings.set_setting("network/language_server/remote_host", "127.0.0.1")
                settings.set_setting("network/language_server/remote_port", port)
                var service := _service()
                if service == null:
                    return false
                return service.install(EditorInterface, "127.0.0.1", port, "127.0.0.1", 6099) == OK

            # Steers ONLY the client endpoint (repeat install writes endpoints back); the
            # server's listen port is untouched. Used to aim the client at a closed port
            # for the degraded-path probe while the server still boots onto the real one.
            func _steer_lsp_client(port: int) -> bool:
                var service := _service()
                if service == null:
                    return false
                return service.install(EditorInterface, "127.0.0.1", port, "127.0.0.1", 6099) == OK

            # `min_epoch` proves a fresh handshake happened (reconnect), not just a steady
            # READY state left over from before a server restart.
            func _wait_lsp_ready(timeout_sec: float, min_epoch: int = 1) -> bool:
                var deadline := Time.get_ticks_msec() + int(timeout_sec * 1000.0)
                while Time.get_ticks_msec() < deadline:
                    var service := _service()
                    if service != null and service.is_lsp_ready():
                        var client = service.get_lsp_client()
                        if client != null and client.get_ready_epoch() >= min_epoch:
                            return true
                    await get_tree().process_frame
                return false

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
                elif mode == "lsp_connect":
                    await _run_lsp_connect_mode()
                elif mode == "lsp_validate":
                    await _run_lsp_validate_mode()
                elif mode == "lsp_fifo":
                    await _run_lsp_fifo_mode()
                elif mode == "lsp_runtime_enable":
                    await _run_lsp_runtime_enable_mode()
                elif mode == "lsp_disable_before_prime":
                    await _run_lsp_disable_before_prime_mode()
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

            # LSP connection lifecycle: the plugin loads long before the GDScript language
            # server starts listening (editor-ready), so the client's capped backoff retry
            # must absorb the startup window. Reconnect resilience is then exercised via an
            # endpoint-steering drop and a plugin disable/enable cycle; a server-side restart
            # triggered by settings changes cannot be driven from script (the engine reacts
            # only to NOTIFICATION_EDITOR_SETTINGS_CHANGED, emitted by C++ UI flows).
            func _run_lsp_connect_mode() -> void:
                var port := int(_config["lsp_port"])
                var closed_port := int(_config["closed_port"])
                _step("lsp_endpoint", _configure_lsp_port(port))
                var service := _service()
                if service == null:
                    _step("lsp_ready", false, "no GdccEditorService")
                    return
                _step("lsp_ready", await _wait_lsp_ready(45.0))
                var epoch: int = service.get_lsp_client().get_ready_epoch()
                # Forced drop: steering the client at a closed port severs the READY link.
                _steer_lsp_client(closed_port)
                var dropped := false
                var drop_deadline := Time.get_ticks_msec() + 15000
                while Time.get_ticks_msec() < drop_deadline and not dropped:
                    dropped = not service.is_lsp_ready()
                    await get_tree().process_frame
                _step("reconnect_drop", dropped)
                # Prove the backoff actually retried the dead endpoint: wait for a real
                # connect failure to be recorded ("endpoint changed" from the steering
                # disconnect itself does not count).
                var saw_retry_failure := false
                var retry_deadline := Time.get_ticks_msec() + 8000
                while Time.get_ticks_msec() < retry_deadline and not saw_retry_failure:
                    saw_retry_failure = service.get_lsp_client().get_last_error().begins_with("connect")
                    await get_tree().process_frame
                _step("reconnect_attempts", saw_retry_failure, service.get_lsp_client().get_last_error())
                # Back to the real endpoint: backoff must redial and re-handshake.
                _steer_lsp_client(port)
                _step("reconnect_recover", await _wait_lsp_ready(45.0, epoch + 1))
                var epoch2: int = service.get_lsp_client().get_ready_epoch()
                # Plugin disable/enable: uninstall stops the client, install restarts it —
                # a full reconnect through the production lifecycle path.
                EditorInterface.set_plugin_enabled("gdcc", false)
                await get_tree().process_frame
                await get_tree().process_frame
                EditorInterface.set_plugin_enabled("gdcc", true)
                await get_tree().process_frame
                _step("plugin_cycle_reconnect", await _wait_lsp_ready(45.0, epoch2 + 1))
                # Thread mode was primed at boot (server not yet listening when the plugin
                # flipped the setting), so a disable/enable cycle must NOT mislatch the
                # session blocking-unsafe — inline waits keep working against the threaded
                # server.
                _step("blocking_safe_after_cycle",
                        service.lsp_sync_and_wait("res://gd3_probe_cycle.gd3", "extends Node\\n", 5000))
                # Behavioral re-probe: a manually raised unsafe flag on a genuinely threaded
                # server must be cleared by a successful bounded probe (a main-thread-polled
                # server cannot answer inline waits — it gets no frames while we spin).
                service.lsp_blocking_unsafe = true
                var reprobe_ok: bool = service.lsp_sync_and_wait("res://gd3_probe_cycle.gd3", "extends Node\\n", 5000)
                _step("thread_reprobe_clears_flag", reprobe_ok and not service.lsp_blocking_unsafe,
                        "flag=" + str(service.lsp_blocking_unsafe))
                _step("survived", true)

            # `_validate` diagnostic merge (plan §4.2 steps 1-2 + degrade shapes). Retried
            # calls absorb a slow first publish; assertions always run on the final content.
            func _run_lsp_validate_mode() -> void:
                var lang := _find_gd3_language()
                if lang == null:
                    _step("lsp_endpoint_closed", false, "GD3 language not registered")
                    return
                # The server's eventual listen port must land in EditorSettings BEFORE the
                # server starts (editor-ready); only the client is steered to a closed port
                # for the degraded probe, where `_validate` must answer the safe shape.
                var port := int(_config["lsp_port"])
                var closed_port := int(_config["closed_port"])
                _step("lsp_endpoint_closed", _configure_lsp_port(port) and _steer_lsp_client(closed_port))
                await get_tree().create_timer(0.5).timeout
                var deg_path := "res://gd3_lsp_error.gd3"
                var deg: Dictionary = lang._validate("extends Node\\nfunc broken( -> void:\\n    pass\\n", deg_path, true, true, true, true)
                var deg_errors: Array = deg.get("errors", [])
                _step("validate_degraded", deg.get("valid", false) == true and deg_errors.is_empty(), str(deg))

                _step("lsp_endpoint", _steer_lsp_client(port))
                _step("lsp_ready", await _wait_lsp_ready(45.0))

                var err_path := "res://gd3_lsp_error.gd3"
                var err_source := "extends Node\\n\\nfunc broken( -> void:\\n    pass\\n"
                _write_text_file(err_path, err_source)
                var error_result := await _validate_until_errors(lang, err_source, err_path)
                var err_errors: Array = error_result.get("errors", [])
                var err_ok: bool = error_result.get("valid", true) == false and not err_errors.is_empty()
                var first_line := -1
                for e in err_errors:
                    if not (e is Dictionary):
                        err_ok = false
                        continue
                    # Every error must carry the current file's res:// path, otherwise the
                    # editor would file it under "dependency script errors" (§4.2).
                    if str(e.get("path", "")) != err_path \\
                            or not e.has("line") or not e.has("column") or not e.has("message"):
                        err_ok = false
                if not err_errors.is_empty():
                    first_line = int(err_errors[0].get("line", -1))
                # The unclosed parameter list reports "Expected ')'..." at the `->` (line 3).
                _step("validate_error", err_ok and first_line == 3, str(error_result))

                var clean_path := "res://gd3_lsp_clean.gd3"
                var clean_source := "extends Node\\n\\nfunc get_value() -> int:\\n    return 1\\n"
                _write_text_file(clean_path, clean_source)
                # Anchor "the server actually parsed THIS generation": only a successful
                # bounded wait proves the diagnostics below are fresh — an empty cache read
                # before the first publish would otherwise look identical to "clean".
                var clean_service := _service()
                var clean_synced: bool = clean_service.lsp_sync_and_wait(clean_path, clean_source, 15000)
                var clean_result: Dictionary = lang._validate(clean_source, clean_path, true, true, true, true)
                var clean_errors: Array = clean_result.get("errors", [])
                _step("validate_clean", clean_synced and clean_result.get("valid", false) == true and clean_errors.is_empty(),
                        "synced=" + str(clean_synced) + " " + str(clean_result))

                var warn_path := "res://gd3_lsp_warn.gd3"
                var warn_source := "extends Node\\n\\nfunc f() -> void:\\n    var unused = 1\\n"
                _write_text_file(warn_path, warn_source)
                var warn_result := await _validate_until_warnings(lang, warn_source, warn_path)
                var warns: Array = warn_result.get("warnings", [])
                var warn_ok := not warns.is_empty()
                warn_ok = warn_ok and warn_result.get("valid", false) == true
                if not warns.is_empty():
                    var w: Dictionary = warns[0]
                    # Missing keys are silently dropped by the engine — pin all five down.
                    for key in ["start_line", "end_line", "code", "string_code", "message"]:
                        if not w.has(key):
                            warn_ok = false
                    warn_ok = warn_ok and int(w.get("start_line", -1)) == 4 \\
                            and int(w.get("end_line", -1)) == 4 \\
                            and int(w.get("code", -1)) == 0 \\
                            and str(w.get("string_code", "")) == "GDSCRIPT_LSP"
                _step("validate_warning", warn_ok, str(warn_result))

                # R11 anchor. Verified engine fact (extend_parser.cpp update_diagnostics):
                # the server reports every diagnostic at the error line's first
                # non-whitespace character — `character` is a code-point count of the
                # indent, never a token position — so the UTF-16-vs-code-point concern
                # cannot manifest on this path. What must hold: a line whose content is
                # multibyte (non-BMP emoji here) still maps to the correct LINE and the
                # indent-derived column (4 spaces → column 5).
                var cjk_path := "res://gd3_lsp_cjk.gd3"
                var cjk_source := "extends Node\\nfunc f() -> void:\\n    var s = \\"🙂\\" + \\"abc\\n"
                _write_text_file(cjk_path, cjk_source)
                var cjk_result := await _validate_until_errors(lang, cjk_source, cjk_path)
                var cjk_errors: Array = cjk_result.get("errors", [])
                var cjk_ok := false
                var cjk_detail := str(cjk_errors)
                for e in cjk_errors:
                    if e is Dictionary and str(e.get("message", "")).contains("Unterminated string"):
                        cjk_detail = str(e)
                        cjk_ok = int(e.get("line", -1)) == 3 and int(e.get("column", -1)) == 5
                _step("validate_cjk_columns", cjk_ok, cjk_detail)

                for p in [err_path, clean_path, warn_path, cjk_path]:
                    DirAccess.remove_absolute(ProjectSettings.globalize_path(p))

                # Disabled state: the service is UNINSTALLED and the resident language must
                # still answer the safe valid:true shape (plan §3.5 entry-point contract).
                EditorInterface.set_plugin_enabled("gdcc", false)
                await get_tree().process_frame
                await get_tree().process_frame
                var resident: ScriptLanguage = _service().get_language_instance()
                var dis: Dictionary = resident._validate("extends Node\\n", "res://x.gd3", true, true, true, true)
                var dis_errors: Array = dis.get("errors", [])
                _step("disabled_validate_safe", dis.get("valid", false) == true and dis_errors.is_empty(), str(dis))
                EditorInterface.set_plugin_enabled("gdcc", true)
                await get_tree().process_frame
                await get_tree().process_frame
                _step("survived", true)

            # Diagnostics carry no version field, so attribution must follow the per-URI
            # generation FIFO: g1 and g2 are sent back-to-back, then g1's notification must
            # still bind to g1 (v1's error), not to whichever text is newest.
            func _run_lsp_fifo_mode() -> void:
                var port := int(_config["lsp_port"])
                _step("lsp_endpoint", _configure_lsp_port(port))
                if not await _wait_lsp_ready(45.0):
                    _step("lsp_ready", false, "timeout")
                    return
                _step("lsp_ready", true)
                var lsp = _service().get_lsp_client()
                var uri: String = lsp.path_to_uri(ProjectSettings.globalize_path("res://gd3_lsp_fifo.gd3"))
                var v1 := "extends Node\\nvar x = = 1\\n"
                var v2 := "extends Node\\nvar x = 1\\n"
                var g1: int = lsp.open_document(uri, v1)
                var g2: int = lsp.change_document(uri, v2)
                _step("fifo_open_change", g1 > 0 and g2 == g1 + 1, "g1=" + str(g1) + " g2=" + str(g2))
                # Both notifications may arrive batched in one read; attribution is proven
                # through the retained per-generation bindings, not the latest cache.
                var got1: bool = lsp.wait_diagnostics(uri, g1, 15000)
                var d1: Array = lsp.get_diagnostics_for_generation(uri, g1)
                var g1_ok: bool = got1 and lsp.has_diagnostics_for_generation(uri, g1)
                if g1_ok:
                    var has_line2_error := false
                    for d in d1:
                        if d is Dictionary and int(d.get("severity", 0)) == 1:
                            var r: Dictionary = d.get("range", {})
                            var s: Dictionary = r.get("start", {})
                            if int(s.get("line", -1)) == 1:
                                has_line2_error = true
                    g1_ok = has_line2_error
                _step("fifo_g1_diagnostics", g1_ok, str(d1))
                var got2: bool = lsp.wait_diagnostics(uri, g2, 15000)
                var d2: Array = lsp.get_diagnostics_for_generation(uri, g2)
                var g2_ok: bool = got2 and lsp.has_diagnostics_for_generation(uri, g2)
                if g2_ok:
                    for d in d2:
                        if d is Dictionary and int(d.get("severity", 0)) == 1:
                            g2_ok = false
                    # The latest cache must hold g2's (empty) diagnostics once both landed.
                    if not lsp.get_diagnostics(uri).is_empty():
                        g2_ok = false
                _step("fifo_g2_diagnostics", g2_ok, str(d2))
                # Regression (review): a timed-out wait must NOT clear the pending queue —
                # clearing would let the late g3 answer bind to the next generation sent
                # after the clear. Forced zero-budget timeout; the FIFO must self-realign:
                # late v3 → g3 (error), v4 → g4 (clean), never crossed.
                var v3 := "extends Node\\nvar y = = 2\\n"
                var v4 := "extends Node\\nvar y = 2\\n"
                var g3: int = lsp.change_document(uri, v3)
                lsp.wait_diagnostics(uri, g3, 0)
                var g4: int = lsp.change_document(uri, v4)
                var got4: bool = lsp.wait_diagnostics(uri, g4, 15000)
                var realign_ok: bool = got4 \\
                        and lsp.has_diagnostics_for_generation(uri, g3) \\
                        and lsp.has_diagnostics_for_generation(uri, g4)
                if realign_ok:
                    var late3: Array = lsp.get_diagnostics_for_generation(uri, g3)
                    var late4: Array = lsp.get_diagnostics_for_generation(uri, g4)
                    var late3_has_error := false
                    for d in late3:
                        if d is Dictionary and int(d.get("severity", 0)) == 1:
                            late3_has_error = true
                    for d in late4:
                        if d is Dictionary and int(d.get("severity", 0)) == 1:
                            realign_ok = false
                    realign_ok = realign_ok and late3_has_error
                _step("fifo_timeout_realign", realign_ok,
                        "g3=" + str(lsp.get_diagnostics_for_generation(uri, g3))
                        + " g4=" + str(lsp.get_diagnostics_for_generation(uri, g4)))
                # Blocking request/response round-trip on the same connection.
                var resp: Dictionary = lsp.request_blocking("textDocument/documentSymbol", {"textDocument": {"uri": uri}}, 10000)
                _step("blocking_request", resp.get("ok", false) == true, str(resp))
                lsp.close_document(uri)
                _step("survived", true)

            # Runtime-enable path (gdcc disabled at boot): the language server starts with
            # thread mode OFF and a programmatic `set_setting` cannot restart it (the
            # settings-changed notification is C++-only, §2.6). The plugin must mark the
            # session blocking-unsafe: inline waits refused, sync still sent, diagnostics
            # surface asynchronously on editor beats.
            func _run_lsp_runtime_enable_mode() -> void:
                var port := int(_config["lsp_port"])
                var settings := EditorInterface.get_editor_settings()
                settings.set_setting("network/language_server/remote_host", "127.0.0.1")
                settings.set_setting("network/language_server/remote_port", port)
                _step("lsp_port_set", true)
                var fs := EditorInterface.get_resource_filesystem()
                var ready_deadline := Time.get_ticks_msec() + 60000
                while fs.is_scanning() and Time.get_ticks_msec() < ready_deadline:
                    await get_tree().process_frame
                # The engine's server (unmanaged by the disabled plugin) starts in non-thread
                # mode; the harness also pins its port via --lsp-port. Generous poll window —
                # server start can lag the filesystem scan by a beat.
                var server_up := false
                var up_deadline := Time.get_ticks_msec() + 30000
                while Time.get_ticks_msec() < up_deadline and not server_up:
                    server_up = await _port_open("127.0.0.1", port)
                _step("editor_ready", not fs.is_scanning() and server_up)
                EditorInterface.set_plugin_enabled("gdcc", true)
                await get_tree().process_frame
                await get_tree().process_frame
                var service := _service()
                _step("plugin_enabled", service != null and service.is_active())
                if service == null:
                    return
                _step("lsp_ready", await _wait_lsp_ready(45.0))
                var rt_path := "res://gd3_lsp_runtime.gd3"
                var rt_source := "extends Node\\n\\nfunc broken( -> void:\\n    pass\\n"
                _write_text_file(rt_path, rt_source)
                # The session must be marked blocking-unsafe AND the refusal must be fast
                # (an unmarked gate would burn the full budget starving the main-thread
                # server, then also return false).
                var wait_start := Time.get_ticks_msec()
                var wait_refused: bool = not service.lsp_sync_and_wait(rt_path, rt_source, 2000)
                var wait_elapsed := Time.get_ticks_msec() - wait_start
                _step("blocking_refused", service.lsp_blocking_unsafe and wait_refused
                        and service.is_lsp_ready() and wait_elapsed < 1000,
                        "flag=" + str(service.lsp_blocking_unsafe) + " elapsed_ms=" + str(wait_elapsed))
                var lang := _find_gd3_language()
                var async_result := await _validate_until_errors(lang, rt_source, rt_path)
                var async_errors: Array = async_result.get("errors", [])
                _step("async_diagnostics_surface", not async_errors.is_empty(), str(async_result))
                DirAccess.remove_absolute(ProjectSettings.globalize_path(rt_path))
                _step("survived", true)

            # Lifecycle edge (review): gdcc primed thread mode at boot, but the plugin is
            # disabled BEFORE the server starts listening — the restored setting makes the
            # server start main-thread-polled, so the stale prime must be dropped and the
            # next enable must re-probe (and find the listening, non-threaded server).
            func _run_lsp_disable_before_prime_mode() -> void:
                var port := int(_config["lsp_port"])
                var settings := EditorInterface.get_editor_settings()
                settings.set_setting("network/language_server/remote_host", "127.0.0.1")
                settings.set_setting("network/language_server/remote_port", port)
                _step("lsp_port_set", true)
                # Plugin init runs long before the server's editor-ready start, so this
                # disable lands inside the "not listening yet" window deterministically.
                EditorInterface.set_plugin_enabled("gdcc", false)
                await get_tree().process_frame
                await get_tree().process_frame
                var service := _service()
                _step("disabled_before_listen", service != null and not service.lsp_thread_primed,
                        "primed=" + str(service.lsp_thread_primed))
                if service == null:
                    return
                var fs := EditorInterface.get_resource_filesystem()
                var ready_deadline := Time.get_ticks_msec() + 60000
                while fs.is_scanning() and Time.get_ticks_msec() < ready_deadline:
                    await get_tree().process_frame
                var server_up := false
                var up_deadline := Time.get_ticks_msec() + 30000
                while Time.get_ticks_msec() < up_deadline and not server_up:
                    server_up = await _port_open("127.0.0.1", port)
                _step("editor_ready", not fs.is_scanning() and server_up)
                EditorInterface.set_plugin_enabled("gdcc", true)
                await get_tree().process_frame
                await get_tree().process_frame
                _step("plugin_reenabled", service.is_active() and service.lsp_blocking_unsafe,
                        "unsafe=" + str(service.lsp_blocking_unsafe))
                _step("lsp_ready", await _wait_lsp_ready(45.0))
                var rt_path := "res://gd3_lsp_reprime.gd3"
                var rt_source := "extends Node\\n\\nfunc broken( -> void:\\n    pass\\n"
                _write_text_file(rt_path, rt_source)
                var wait_start := Time.get_ticks_msec()
                var wait_refused: bool = not service.lsp_sync_and_wait(rt_path, rt_source, 2000)
                var wait_elapsed := Time.get_ticks_msec() - wait_start
                _step("blocking_refused", wait_refused and wait_elapsed < 1000,
                        "elapsed_ms=" + str(wait_elapsed))
                var lang := _find_gd3_language()
                var async_result := await _validate_until_errors(lang, rt_source, rt_path)
                var async_errors: Array = async_result.get("errors", [])
                _step("async_diagnostics_surface", not async_errors.is_empty(), str(async_result))
                DirAccess.remove_absolute(ProjectSettings.globalize_path(rt_path))
                _step("survived", true)

            func _validate_until_errors(lang: ScriptLanguage, source: String, path: String) -> Dictionary:
                var deadline := Time.get_ticks_msec() + 15000
                var result: Dictionary = lang._validate(source, path, true, true, true, true)
                var errors: Array = result.get("errors", [])
                while errors.is_empty() and Time.get_ticks_msec() < deadline:
                    await get_tree().process_frame
                    result = lang._validate(source, path, true, true, true, true)
                    errors = result.get("errors", [])
                return result

            func _validate_until_warnings(lang: ScriptLanguage, source: String, path: String) -> Dictionary:
                var deadline := Time.get_ticks_msec() + 15000
                var result: Dictionary = lang._validate(source, path, true, true, true, true)
                var warnings: Array = result.get("warnings", [])
                while warnings.is_empty() and Time.get_ticks_msec() < deadline:
                    await get_tree().process_frame
                    result = lang._validate(source, path, true, true, true, true)
                    warnings = result.get("warnings", [])
                return result
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

    /// Phase 2 acceptance: the LSP client reaches READY through its backoff retry on a fresh
    /// editor boot (plugin loads before the server listens), and reconnects after a forced
    /// drop and after a plugin disable/enable cycle.
    @Test
    void lspConnectsWithRetryAndReconnectsAfterDrops() throws Exception {
        var config = new JsonObject();
        config.addProperty("lsp_port", findFreePort());
        config.addProperty("closed_port", findFreePort());
        runCase("lsp_connect", config);
    }

    /// Phase 2 acceptance: `_validate` merges GDScript LSP diagnostics — error/clean/warning
    /// samples with exact line anchoring and mandatory `path`, non-BMP column accounting,
    /// plus the degraded (no server) and uninstalled-service safe answers.
    @Test
    void lspDiagnosticsDriveValidate() throws Exception {
        var config = new JsonObject();
        config.addProperty("lsp_port", findFreePort());
        config.addProperty("closed_port", findFreePort());
        runCase("lsp_validate", config);
    }

    /// Phase 2 acceptance: `publishDiagnostics` carries no version, so two generations sent
    /// back-to-back must be attributed to their notifications strictly FIFO.
    @Test
    void lspDiagnosticsFollowGenerationFifo() throws Exception {
        var config = new JsonObject();
        config.addProperty("lsp_port", findFreePort());
        runCase("lsp_fifo", config);
    }

    /// Phase 2 (review finding): enabling the plugin at runtime flips `use_thread` too late
    /// for the already-running server (settings-changed notification is C++-only). Inline
    /// waits must be refused for the session while diagnostics still arrive asynchronously.
    @Test
    void lspRuntimeEnableRefusesBlockingButKeepsAsyncDiagnostics() throws Exception {
        var config = new JsonObject();
        config.addProperty("lsp_port", findFreePort());
        config.addProperty("gdcc_enabled_at_boot", false);
        runCase("lsp_runtime_enable", config);
    }

    /// Phase 2 (review round 3): disabling the plugin BEFORE the primed server ever listened
    /// must un-prime thread mode (the restored setting makes the server start
    /// main-thread-polled), so the next enable re-probes and refuses inline waits.
    @Test
    void lspDisableBeforeServerPrimeReprobesOnNextEnable() throws Exception {
        var config = new JsonObject();
        config.addProperty("lsp_port", findFreePort());
        runCase("lsp_disable_before_prime", config);
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
        // `gdcc_enabled_at_boot=false` keeps the gdcc plugin disabled through editor startup
        // (runtime-enable cases); the driver remains enabled so it can flip it on later.
        var enableGdccAtBoot = !config.has("gdcc_enabled_at_boot")
                || config.get("gdcc_enabled_at_boot").getAsBoolean();
        patchEnabledPlugins(projectDir, enableGdccAtBoot);

        var lspPort = config.has("lsp_port") ? config.get("lsp_port").getAsInt() : -1;
        var output = runEditor(godotBinary, projectDir, caseDir, lspPort);
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
    /// usage); fails fast when the anchor drifts. `enableGdcc=false` drops gdcc from the list
    /// entirely (runtime-enable cases) while keeping the driver.
    private static void patchEnabledPlugins(Path projectDir, boolean enableGdcc) throws IOException {
        var projectFile = projectDir.resolve("project.godot");
        var content = Files.readString(projectFile);
        var anchor = "enabled=PackedStringArray(\"res://addons/gdcc/plugin.cfg\")";
        if (!content.contains(anchor)) {
            throw new IllegalStateException("project.godot enabled-plugins anchor not found:\n" + content);
        }
        Files.writeString(projectFile, content.replace(anchor,
                enableGdcc
                        ? "enabled=PackedStringArray(\"res://addons/gdcc/plugin.cfg\", "
                                + "\"res://addons/gdcc_test_driver/plugin.cfg\")"
                        : "enabled=PackedStringArray(\"res://addons/gdcc_test_driver/plugin.cfg\")"));
    }

    /// Runs `godot --headless --editor --path <copy> [--lsp-port <P>] --quit-after <N>` and
    /// returns the combined output. The CLI LSP port override (engine-supported, takes priority
    /// over EditorSettings) pins the server's listen port before any plugin code runs — setting
    /// `remote_port` from the driver would race the server's post-editor-ready start.
    /// Config directories are redirected into the case dir so the editor's settings writes
    /// (use_thread, the launch command, layout state) never touch the real user profile.
    private static String runEditor(Path godotBinary, Path projectDir, Path caseDir, int lspPort)
            throws IOException, InterruptedException {
        var isolatedConfig = Files.createDirectories(caseDir.resolve("config-home"));
        var command = new ArrayList<>(List.of(
                godotBinary.toString(),
                "--headless",
                "--editor",
                "--path", projectDir.toAbsolutePath().toString()));
        if (lspPort > 0) {
            command.add("--lsp-port");
            command.add(String.valueOf(lspPort));
        }
        command.add("--quit-after");
        command.add(String.valueOf(QUIT_AFTER_FRAMES));
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

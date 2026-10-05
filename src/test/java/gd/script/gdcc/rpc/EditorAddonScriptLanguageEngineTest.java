package gd.script.gdcc.rpc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import gd.script.gdcc.api.API;
import gd.script.gdcc.api.CompileResult;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.GdextensionMetadataFile;
import gd.script.gdcc.backend.c.build.GodotGdextensionTestRunner;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import gd.script.gdcc.backend.c.build.ZigUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
///   and never crashes the editor (negative path). The failure report is platform-dependent
///   (Godot 4.5 `OS.create_process`): on Windows CreateProcessW rejects the executable
///   synchronously, the binding returns -1 and the launcher prints "OS.create_process
///   failed"; on Unix-likes fork() already succeeded, the child only fails at execvp()
///   (the engine prints "Could not create child process" and SIGKILLs it), so the launcher
///   instead reports the spawned process's early exit. Both paths are accepted; "no service
///   listening" and "editor alive" stay mandatory via the driver's step sequence.
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

    private static final Map<String, List<String>> EXPECTED_STEPS = Map.ofEntries(
            Map.entry("language", List.of(
                    "config", "language_registered", "language_identity", "load_roundtrip",
                    "save_roundtrip", "save_bad_path", "reload_from_disk", "threaded_load",
                    "validate_valid_true", "create_script_valid", "header_scan_edges",
                    "disabled_safe", "reenabled_same_instance")),
            Map.entry("launch", List.of(
                    "config", "configure", "relaunched", "server_up", "server_stopped_on_disable")),
            Map.entry("launch_bad", List.of("config", "configure", "relaunched", "no_server_started", "editor_alive")),
            Map.entry("launch_none", List.of("config", "relaunched", "passive_no_server", "editor_alive")),
            // Reproduction of the manual-testing crash: the Create Resource dialog instantiates
            // a ClassDB GdccScript (no language setup) and pushes it to the inspector/editor.
            Map.entry("create_resource", List.of(
                    "config", "classdb_instantiate", "property_list", "save", "edit_resource",
                    "disabled_create_safe", "survived")),
            // Phase 2: LSP connection lifecycle (backoff retry across the editor-boot window
            // where the GDScript language server does not listen yet, then drop/reconnect
            // cycles — endpoint steering forces a disconnect, and a plugin disable/enable
            // cycle exercises stop/start re-handshake. Note: the server's runtime restart on
            // `use_thread` changes is driven by NOTIFICATION_EDITOR_SETTINGS_CHANGED, which
            // is emitted only by C++ UI flows and is not reachable from script, so an actual
            // server-side restart cannot be triggered by the driver.
            Map.entry("lsp_connect", List.of(
                    "config", "lsp_endpoint", "lsp_ready", "reconnect_drop",
                    "reconnect_attempts", "reconnect_recover", "plugin_cycle_reconnect",
                    "blocking_safe_after_cycle", "thread_reprobe_clears_flag", "survived")),
            // Phase 2: `_validate` diagnostic merge — degraded path, error/clean/warning
            // samples with exact line anchoring, non-BMP column accounting (R11), and the
            // uninstalled-service safe answer.
            Map.entry("lsp_validate", List.of(
                    "config", "lsp_endpoint_closed", "validate_degraded", "lsp_endpoint",
                    "lsp_ready", "validate_error", "validate_clean", "validate_warning",
                    "validate_cjk_columns", "disabled_validate_safe", "survived")),
            // Phase 2: publishDiagnostics carry no version, so attribution must follow the
            // per-URI generation FIFO (g1 sent, g2 sent, then g1's notification must still
            // bind to g1); plus a blocking request/response round-trip.
            Map.entry("lsp_fifo", List.of(
                    "config", "lsp_endpoint", "lsp_ready", "fifo_open_change",
                    "fifo_g1_diagnostics", "fifo_g2_diagnostics", "fifo_timeout_realign",
                    "blocking_request", "survived")),
            // Phase 2 (review finding): runtime enable flips `use_thread` too late — the
            // server already started main-thread-polled and a programmatic set_setting
            // cannot restart it (§2.6). The session must refuse inline waits (no 150ms
            // starvation per validate) while diagnostics still surface asynchronously.
            Map.entry("lsp_runtime_enable", List.of(
                    "config", "lsp_port_set", "editor_ready", "plugin_enabled", "lsp_ready",
                    "blocking_refused", "async_diagnostics_surface", "survived")),
            // Phase 2 (review round 3): boot-time prime followed by a disable BEFORE the
            // server listened must un-prime (the restored setting makes the server start
            // main-thread-polled); the next enable re-probes and degrades correctly.
            Map.entry("lsp_disable_before_prime", List.of(
                    "config", "lsp_port_set", "disabled_before_listen", "editor_ready",
                    "plugin_reenabled", "lsp_ready", "blocking_refused",
                    "async_diagnostics_surface", "survived")),
            // Phase 3: gdcc diagnostics channel — merge into `_validate` (path-extends
            // error + lowering warning/error pair), displayPath keying for same-basename
            // files, stale-visible retention across an edit and LSP-error suppression
            // negatives, fix-then-clear round, busy coordination, -32001 stale-module
            // rebuild, delete/rename reconciliation with server-side VFS proof, and the
            // ensure_server_hook outage path with recovery.
            Map.entry("gdcc_diag", List.of(
                    "config", "service_ready", "busy_probe_connected", "fixtures_written",
                    "analysis_ready", "validate_gdcc_error", "validate_lowering_pair",
                    "display_path_keying", "stale_version_still_visible",
                    "lsp_error_suppresses_gdcc", "fix_clears_after_round", "busy_drained",
                    "stale_module_rebuild", "delete_reconciles", "rename_reconciles",
                    "deletion_triggers_reanalysis", "delete_with_dirty_buffer",
                    "hook_invoked_on_outage", "hook_ok_ping_fail_capped",
                    "stale_hook_answer_dropped", "recovered_after_hook",
                    "hook_pending_uninstall_recovers", "survived")),
            // Phase 10: dock Compile copies the auto-synced diagnostics module server-side,
            // compiles the copy with a real native build, and never blocks the diagnostics
            // module gate; the second compile replaces the stale copy (-32001 path).
                    Map.entry("dock_compile", List.of(
                    "config", "service_ready", "fixture_written", "broken_fixture_removed",
                    "fixture_synced", "copy_created", "diag_alive_during_compile",
                    "compile_succeeded", "fixture_resynced", "recompile_replaces_copy",
                    "diag_still_responsive", "survived")),
            // Phase 4: `_complete_code` — degraded answers (client not READY / no sentinel /
            // disabled service), white-box pins of the kind table and the insert-text
            // precedence (the server's emitted kind set is not controllable, so the mapping
            // contract is anchored directly), keyword + member completion through the real
            // GDScript LSP with the seven mandatory option keys, the CJK caret anchor (R11
            // residual: text follows the caret, so a UTF-16/byte miscount loses the member
            // context), a per-generation FIFO attribution check on a completion-synced URI
            // (the completion traffic's queued generations must not desync the diagnostic
            // binding), and a post-disable/enable `_validate` sanity check.
            Map.entry("lsp_completion", List.of(
                    "config", "lsp_endpoint_closed", "complete_degraded", "lsp_endpoint",
                    "lsp_ready", "kind_mapping", "insert_text_priority", "complete_keywords",
                    "complete_empty_context", "complete_member", "complete_cjk",
                    "complete_no_sentinel", "fifo_survives_completion",
                    "complete_disabled_safe", "validate_still_healthy", "survived")),
            // Phase 5: diagnostic visibility — the signal-triggered revalidation after a
            // background merge (current-tab + version gated, popup-deferred, ordering-pinned
            // against the idle beat), the version-advance and same-version-remerge
            // positive/negative pair, the non-current-tab drop, the dock status area, and
            // endpoint single-sourcing with live retarget across a plugin toggle.
            Map.entry("diag_revalidate", List.of(
                    "config", "service_ready", "fixtures_written", "analysis_ready",
                    "editor_opened", "signal_revalidates", "same_version_remerge_quiet",
                    "new_version_same_diag_fires", "non_current_tab_dropped",
                    "popup_deferral", "endpoint_retarget_closed", "endpoint_retarget_recover",
                    "endpoint_persists_after_toggle", "status_area", "survived")),
            // Phase 6+7 (plan §7): compile-time `_gdcc_get_metadata` readback through ClassDB
            // (incl. the nested class and the cache-isolation rule), the class_name erasure
            // admission matrix and rewrite/mapping shapes (whitebox), then end-to-end on a
            // per-case compiled fixture: no `hides a native class` pseudo-error, gdcc
            // diagnostics still flow after an edit, and an extension-reload decision flip
            // re-syncs + revalidates the current document.
            Map.entry("class_name_erasure", List.of(
                    "config", "service_ready", "lsp_ready", "metadata_readback",
                    "metadata_inner_and_cache", "admission_matrix", "rewrite_forms",
                    "analysis_ready", "validate_no_hides_error", "editor_opened",
                    "decision_flip", "edit_shows_gdcc_not_hides",
                    "flip_to_no_erasure", "flip_back_to_erasure", "survived")),
            // Phase 8: LSP workspace mirror — project `.gd3` files reach the server's parse
            // cache only via client didOpen (its disk scan indexes `.gd` only). Anchors:
            // nothing is mirrored while the channel is down (negative), the initial replay
            // lands server-side (documentSymbol proof), an unsaved editor buffer wins over
            // disk text, disk-side edits refresh, deletion didCloses, `_validate` keeps its
            // bounded budget during a bulk sync, and a reconnect replays the whole set.
            Map.entry("lsp_workspace", List.of(
                    "config", "fixtures_written", "lsp_endpoint_closed", "mirror_waits_for_ready",
                    "lsp_endpoint", "lsp_ready", "mirror_initial_sync", "mirror_buffer_wins",
                    "mirror_disk_refresh", "mirror_delete_close", "mirror_tombstone_recreated",
                    "validate_budget_during_bulk", "mirror_reconnect_replay", "survived")),
            // Phase 9: `_lookup_code` — degraded answers for the negative paths (client not
            // READY, unknown symbol, disabled service), white-box pins of the hover/
            // definition payload mapping and the URI round-trip, then end-to-end through
            // the real GDScript LSP: same-file hover+definition (LOCAL_VARIABLE type,
            // 1-based location, no `script` key for same-file), cross-file definition via
            // the Phase 8 workspace mirror (`script` resource + provider path), the
            // code-point caret anchor (CJK before the sentinel), and the sentinel-less
            // whole-word fallback.
            Map.entry("lsp_lookup", List.of(
                    "config", "fixtures_written", "lsp_endpoint_closed", "lookup_degraded",
                    "lsp_endpoint", "lsp_ready", "lookup_whitebox", "lookup_local_method",
                    "lookup_cross_file", "lookup_class_name_gap", "lookup_cjk",
                    "lookup_unknown_symbol", "lookup_no_sentinel_fallback",
                    "lookup_disabled_safe", "survived")),
            // Post-Phase-10 fixes: `gdcc/sync/excluded_globs` — the default set keeps the
            // addon's own .gd3 files out of the diagnostics module AND the LSP workspace
            // mirror, an excluded file OPEN in an editor tab is temporarily included
            // (admitted via notify, evicted after the tab closes), and a settings edit
            // reconciles live in BOTH directions (through the plugin's
            // ProjectSettings.settings_changed wiring — the driver never sends a manual
            // filesystem notify for those steps). The probe is mirrored on the LSP before
            // exclusion, so the exclude step also proves the mirror close path cannot
            // bounce close→sync forever (that loop would freeze the frame pump and starve
            // every poll below).
            Map.entry("sync_exclusions", List.of(
                    "config", "service_ready", "default_excludes_addon",
                    "excluded_open_tab_admitted", "excluded_open_kept_across_reconcile",
                    "excluded_simulated_admitted", "excluded_simulated_dropped",
                    "custom_file_synced", "probe_mirrored_lsp",
                    "settings_change_excludes", "mirror_closes_excluded",
                    "buffer_only_uploaded", "buffer_only_excluded", "buffer_only_reincluded",
                    "reinclude_resyncs", "recovery_setup", "recovery_excluded_kept",
                    "recovery_module_rebuilt", "recovery_reuploaded", "survived")),
            Map.entry("lookup_suspended", List.of(
                    "config", "service_ready", "lsp_ready", "lookup_helpers", "editor_opened",
                    "lookup_unavailable", "lookup_keeps_path", "save_on_original_path", "survived")),
            // Phase 9: `_auto_indent_code` — verbatim mirror of the 4.5 GDScript reference
            // algorithm (whitespace-stack nesting, blank/comment passthrough, the dedent
            // quirk, the from_line gate, the spaces-indent setting, and the pathological
            // from>to early break). Exact-output anchors computed by hand from the C++
            // reference; needs no LSP.
            Map.entry("auto_indent", List.of(
                    "config", "indent_tabs_basic", "indent_blank_comment_preserved",
                    "indent_dedent_stack_quirk", "indent_from_line_gate",
                    "indent_spaces_setting", "indent_inverted_range", "survived"))
    );

    /// Interpreted driver plugin (gdcc feature limits do not apply to it). Every mode records
    /// ordered steps; the Java side requires the exact expected sequence, all `ok`, so an
    /// early bail-out fails loudly instead of silently skipping assertions. The source exceeds
    /// the 64KB class-file limit for one string literal, so it is assembled from method
    /// results — method calls are not compile-time constant expressions, so javac emits a
    /// runtime concat instead of folding the parts back into a single oversized constant.
    private static final String DRIVER_PLUGIN = driverPluginCore() + driverPluginCompletion()
            + driverPluginRevalidate() + driverPluginClassMetadata() + driverPluginWorkspace()
            + driverPluginLookup() + driverPluginExclusions();

    private static String driverPluginCore() {
        return """
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
                    elif mode == "gdcc_diag":
                        await _run_gdcc_diag_mode()
                    elif mode == "dock_compile":
                        await _run_dock_compile_mode()
                    elif mode == "lsp_completion":
                        await _run_lsp_completion_mode()
                    elif mode == "diag_revalidate":
                        await _run_diag_revalidate_mode()
                    elif mode == "class_name_erasure":
                        await _run_class_name_erasure_mode()
                    elif mode == "lsp_workspace":
                        await _run_lsp_workspace_mode()
                    elif mode == "lsp_lookup":
                        await _run_lsp_lookup_mode()
                    elif mode == "lookup_suspended":
                        await _run_lookup_suspended_mode()
                    elif mode == "sync_exclusions":
                        await _run_sync_exclusions_mode()
                    elif mode == "auto_indent":
                        _run_auto_indent_mode()
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
                    # launcher's own log line is asserted from the Java side. Which line appears
                    # is platform-dependent: Windows refuses the spawn synchronously
                    # ("OS.create_process failed"), while on Unix-likes fork() succeeds and the
                    # child dies in execvp() (engine: "Could not create child process"), so the
                    # launcher reports the early exit ("exited before accepting connections").
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
                
                # ---------------- Phase 3: gdcc diagnostics channel ----------------
                # The fixtures' exact frontend diagnostics are pinned Java-side by
                # EditorAddonDiagnosticsFixtureTest; here we assert the addon's mapping,
                # version gating, suppression rules, and module/reconcile lifecycle.
                
                var _busy_log: Array = []
                var _hook_calls: int = 0
                
                func _on_diag_busy(delta: int) -> void:
                    _busy_log.append(delta)
                
                # Stand-in for the launcher hook: records the invocation and reports failure so
                # the service backs off instead of looping hot.
                func _record_ensure_hook(_hook_host: String, _hook_port: int, on_ready: Callable) -> void:
                    _hook_calls += 1
                    on_ready.call(ERR_CANT_CONNECT, false)
                
                # Hook variant whose probe always "succeeds" — used against the blackhole
                # endpoint (TCP accepts, RPC always 500) to exercise the hook-OK/ping-fail cycle.
                func _record_ensure_ok_hook(_hook_host: String, _hook_port: int, on_ready: Callable) -> void:
                    _hook_calls += 1
                    on_ready.call(OK, false)
                
                # Hook variant that never answers on its own: the driver fires the recorded
                # replies manually to control stale-answer timing across retargets (review
                # finding: a superseded hook's late answer must not touch the new window).
                var _deferred_replies: Array = []
                
                func _record_ensure_defer_hook(_hook_host: String, _hook_port: int, on_ready: Callable) -> void:
                    _hook_calls += 1
                    _deferred_replies.append(on_ready)
                
                # Fresh-round wait: the cache serves STALE entries by design now (they stay
                # visible until the fresh round replaces them), so a non-empty read alone
                # cannot prove the requested version landed — require freshness AND items.
                func _wait_diag_items(service: Node, path: String, version: int, timeout_sec: float) -> bool:
                    var deadline := Time.get_ticks_msec() + int(timeout_sec * 1000.0)
                    while Time.get_ticks_msec() < deadline:
                        if service.is_diag_version_current(path, version) \
                                and not service.gdcc_diagnostics_for(path, version).is_empty():
                            return true
                        await get_tree().process_frame
                    return false
                
                func _wait_diag_current(service: Node, path: String, version: int, timeout_sec: float) -> bool:
                    var deadline := Time.get_ticks_msec() + int(timeout_sec * 1000.0)
                    while Time.get_ticks_msec() < deadline:
                        if service.is_diag_version_current(path, version):
                            return true
                        await get_tree().process_frame
                    return false
                
                func _wait_diag_ready(service: Node, timeout_sec: float) -> bool:
                    var deadline := Time.get_ticks_msec() + int(timeout_sec * 1000.0)
                    while Time.get_ticks_msec() < deadline:
                        if service.is_diag_ready():
                            return true
                        await get_tree().process_frame
                    return false
                
                func _run_gdcc_diag_mode() -> void:
                    var service := _service()
                    var lang := _find_gd3_language()
                    if service == null or lang == null:
                        _step("service_ready", false, "GdccEditorService or GD3 language missing")
                        return
                    var lsp_port := int(_config["lsp_port"])
                    var rpc_port := int(_config["rpc_port"])
                    # HTTP 500 blackhole (fails fast) for the hook-outage step.
                    var blackhole_port := int(_config["blackhole_port"])
                    # Repeat-install retargets both endpoints onto the test-owned servers.
                    _step("service_ready", service.install(EditorInterface, "127.0.0.1", lsp_port, "127.0.0.1", rpc_port) == OK)
                    service.busy_delta.connect(_on_diag_busy)
                    _step("busy_probe_connected", true)
                
                    var base_path := "res://diag_base.gd"
                    var child_path := "res://diag_path_child.gd3"
                    var child_src := "class_name DiagPathChild\\nextends \\"res://diag_base.gd\\"\\n"
                    var lowering_path := "res://diag_lowering.gd3"
                    var lowering_src := "class_name DiagLowering\\nextends Node\\n\\n@onready var camera = $Camera3D\\n"
                    var dup_a_path := "res://dup_a/same.gd3"
                    var dup_a_src := "class_name DupA\\nextends \\"res://diag_base.gd\\"\\n"
                    var dup_b_path := "res://dup_b/same.gd3"
                    var dup_b_src := "class_name DupB\\nextends \\"res://diag_base.gd\\"\\n"
                    var gone_path := "res://diag_gone.gd3"
                    var gone_src := "class_name DiagGone\\nextends \\"res://diag_base.gd\\"\\n"
                    DirAccess.make_dir_recursive_absolute(ProjectSettings.globalize_path("res://dup_a"))
                    DirAccess.make_dir_recursive_absolute(ProjectSettings.globalize_path("res://dup_b"))
                    _write_text_file(base_path, "extends Node\\n")
                    _write_text_file(child_path, child_src)
                    _write_text_file(lowering_path, lowering_src)
                    _write_text_file(dup_a_path, dup_a_src)
                    _write_text_file(dup_b_path, dup_b_src)
                    _write_text_file(gone_path, gone_src)
                    # Deterministic reconcile trigger (the editor's own filesystem signal may
                    # coalesce onto the same path; both entries are equivalent).
                    service.notify_filesystem_changed()
                    _step("fixtures_written", true)
                
                    # One initial round mirrors every fixture (each at content version 1) and
                    # analyzes the module: all five fixtures must carry diagnostics.
                    var ready_ok: bool = await _wait_diag_ready(service, 45.0)
                    ready_ok = ready_ok and await _wait_diag_items(service, child_path, 1, 60.0)
                    ready_ok = ready_ok and await _wait_diag_items(service, lowering_path, 1, 60.0)
                    ready_ok = ready_ok and await _wait_diag_items(service, dup_a_path, 1, 60.0)
                    ready_ok = ready_ok and await _wait_diag_items(service, dup_b_path, 1, 60.0)
                    ready_ok = ready_ok and await _wait_diag_items(service, gone_path, 1, 60.0)
                    _step("analysis_ready", ready_ok)
                
                    # gdcc-only error surfaces on the next validation beat. The retry absorbs the
                    # editor's async filesystem scan (the LSP may briefly miss diag_base.gd);
                    # only a state where EVERY error carries the gdcc prefix is accepted, which
                    # simultaneously proves the LSP stayed silent on legal path-extends.
                    var child_result := {}
                    var child_ok := false
                    var child_deadline := Time.get_ticks_msec() + 30000
                    while Time.get_ticks_msec() < child_deadline and not child_ok:
                        child_result = lang._validate(child_src, child_path, true, true, true, true)
                        var child_errors: Array = child_result.get("errors", [])
                        child_ok = child_errors.size() == 1 and child_result.get("valid", true) == false
                        if child_ok:
                            var ce: Dictionary = child_errors[0]
                            child_ok = str(ce.get("message", "")).begins_with("[gdcc sema.class_skeleton]") \
                                    and int(ce.get("line", -1)) == 1 \
                                    and str(ce.get("path", "")) == child_path \
                                    and str(ce.get("message", "")).contains("DiagPathChild")
                        if not child_ok:
                            await get_tree().process_frame
                    _step("validate_gdcc_error", child_ok, str(child_result))
                
                    # includeLowering=true surfaces the compile-only error AND the deferred
                    # warning as a pair on the same 1-based anchor (line 4, column 23).
                    var lower_result: Dictionary = lang._validate(lowering_src, lowering_path, true, true, true, true)
                    var lower_errors: Array = lower_result.get("errors", [])
                    var lower_warnings: Array = lower_result.get("warnings", [])
                    var lower_ok: bool = lower_result.get("valid", true) == false
                    var error_found := false
                    for e in lower_errors:
                        if str(e.get("message", "")).begins_with("[gdcc sema.compile_check]") \
                                and int(e.get("line", -1)) == 4 and int(e.get("column", -1)) == 23:
                            error_found = true
                    lower_ok = lower_ok and error_found
                    var warning_found := false
                    for w in lower_warnings:
                        var five_keys := true
                        for key in ["start_line", "end_line", "code", "string_code", "message"]:
                            if not w.has(key):
                                five_keys = false
                        if five_keys and str(w.get("string_code", "")) == "sema.deferred_expression_resolution" \
                                and int(w.get("start_line", -1)) == 4 and int(w.get("end_line", -1)) == 4 \
                                and int(w.get("code", -1)) == 0 \
                                and str(w.get("message", "")).begins_with("[gdcc sema.deferred_expression_resolution]"):
                            warning_found = true
                    _step("validate_lowering_pair", lower_ok and warning_found, str(lower_result))
                
                    # Same-basename files: diagnostics key by displayPath, never by basename.
                    var dup_a_items: Array = service.gdcc_diagnostics_for(dup_a_path, 1)
                    var dup_b_items: Array = service.gdcc_diagnostics_for(dup_b_path, 1)
                    var keying_ok: bool = dup_a_items.size() == 1 and dup_b_items.size() == 1
                    if keying_ok:
                        var a_msg := str(dup_a_items[0].get("message", ""))
                        var b_msg := str(dup_b_items[0].get("message", ""))
                        keying_ok = a_msg.contains("DupA") and not a_msg.contains("DupB") \
                                and b_msg.contains("DupB") and not b_msg.contains("DupA")
                    _step("display_path_keying", keying_ok,
                            "a=" + str(dup_a_items.size()) + " b=" + str(dup_b_items.size()))
                
                    # Stale-visible contract (post-Phase-10 fix): the edit bumps the version
                    # instantly, but the previous round's gdcc error must STAY visible — the
                    # editor replaces displayed markers wholesale on every validation beat, so
                    # a version-gated empty read would blank the gutter for the whole
                    # debounce+analysis window and leave the user fixing code blind. The
                    # fresh v2 round then replaces the entry (same diagnostic: comment edit).
                    var edited_src := child_src + "# touched\\n"
                    var stale_result: Dictionary = lang._validate(edited_src, child_path, true, true, true, true)
                    var stale_errors: Array = stale_result.get("errors", [])
                    var stale_visible := false
                    for e in stale_errors:
                        if str(e.get("message", "")).begins_with("[gdcc sema.class_skeleton]"):
                            stale_visible = true
                    var v2_fresh: bool = await _wait_diag_current(service, child_path, 2, 60.0)
                    _step("stale_version_still_visible",
                            stale_visible and stale_result.get("valid", true) == false and v2_fresh,
                            str(stale_result) + " v2_fresh=" + str(v2_fresh))
                
                    # LSP errors suppress gdcc diagnostics even with a fresh cache: the broken
                    # text is analyzed by gdcc too (parse error cached at v3), yet only the LSP
                    # entries may surface. The non-empty cache assertion pins that gdcc
                    # diagnostics DID exist at this version — a vacuously-clean cache would not
                    # prove suppression.
                    var broken_src := "class_name DiagPathChild\nextends Node\nfunc broken( -> void:\n    pass\n"
                    lang._validate(broken_src, child_path, true, true, true, true)
                    var broken_cached: bool = await _wait_diag_current(service, child_path, 3, 60.0)
                    var broken_fresh: Array = service.gdcc_diagnostics_for(child_path, 3)
                    var broken_result: Dictionary = lang._validate(broken_src, child_path, true, true, true, true)
                    var broken_errors: Array = broken_result.get("errors", [])
                    var suppress_ok: bool = broken_cached and not broken_fresh.is_empty() \
                            and not broken_errors.is_empty()
                    for e in broken_errors:
                        if str(e.get("message", "")).begins_with("[gdcc"):
                            suppress_ok = false
                    _step("lsp_error_suppresses_gdcc", suppress_ok,
                            "cached=" + str(broken_cached) + " fresh=" + str(broken_fresh.size())
                            + " " + str(broken_result))
                
                    # Fixing the source clears the diagnostic after the next round (the cache is
                    # REPLACED per round, so a resolved error must not linger).
                    var fixed_src := "class_name DiagPathChild\\nextends Node\\n"
                    lang._validate(fixed_src, child_path, true, true, true, true)
                    var fixed_cached: bool = await _wait_diag_current(service, child_path, 4, 60.0)
                    var fixed_result: Dictionary = lang._validate(fixed_src, child_path, true, true, true, true)
                    var fixed_errors: Array = fixed_result.get("errors", [])
                    _step("fix_clears_after_round",
                            fixed_cached and fixed_result.get("valid", false) == true and fixed_errors.is_empty(),
                            "cached=" + str(fixed_cached) + " " + str(fixed_result))
                
                    # Low-power coordination: the recorder connected mid-hold (install already
                    # held busy), so the log may start with a dangling -1 — assert observed
                    # raises, observed drains, and an idle (drained) final state instead of
                    # exact balance.
                    var busy_ups := 0
                    var busy_downs := 0
                    for delta in _busy_log:
                        if int(delta) > 0:
                            busy_ups += 1
                        else:
                            busy_downs += 1
                    _step("busy_drained", busy_ups > 0 and busy_downs > 0
                            and int(_busy_log[_busy_log.size() - 1]) == -1, str(_busy_log))
                
                    # -32001 stale-module rebuild: plant a same-id remnant while the service is
                    # uninstalled, then re-install — the create must delete+recreate and resume.
                    # The proof of a FRESH post-rebuild round is a version bump: the remnant
                    # carried no files, and the retained pre-uninstall cache (version 1) must not
                    # satisfy a version-2 wait.
                    service.uninstall()
                    var remnant: Dictionary = await service._rpc_client \
                            .create_module(service.get_diag_module_id(), "Stale Remnant").completed
                    var remnant_ok: bool = remnant.get("ok", false) == true
                    var reinstall_ok: bool = service.install(
                            EditorInterface, "127.0.0.1", lsp_port, "127.0.0.1", rpc_port) == OK
                    var rebuild_ok: bool = remnant_ok and reinstall_ok and await _wait_diag_ready(service, 45.0)
                    var lowering_v2 := lowering_src + "# rebuilt\n"
                    lang._validate(lowering_v2, lowering_path, true, true, true, true)
                    rebuild_ok = rebuild_ok and await _wait_diag_items(service, lowering_path, 2, 60.0)
                    _step("stale_module_rebuild", rebuild_ok,
                            "remnant=" + str(remnant_ok) + " reinstall=" + str(reinstall_ok))
                
                    # Deletion: the mirror drops the path locally AND server-side.
                    DirAccess.remove_absolute(ProjectSettings.globalize_path(gone_path))
                    service.notify_filesystem_changed()
                    var delete_ok := false
                    var delete_deadline := Time.get_ticks_msec() + 60000
                    while Time.get_ticks_msec() < delete_deadline and not delete_ok:
                        if not service.is_diag_version_current(gone_path, 1):
                            var gone_read: Dictionary = await service._rpc_client \
                                    .read_file(service.get_diag_module_id(), "/src/diag_gone.gd3").completed
                            if gone_read.get("ok", true) == false:
                                var gone_err: Dictionary = gone_read.get("error", {})
                                delete_ok = int(gone_err.get("code", 0)) == -32005
                        if not delete_ok:
                            await get_tree().process_frame
                    _step("delete_reconciles", delete_ok)
                
                    # Cross-directory rename: the old path's diagnostics vanish (locally AND
                    # server-side) and the new path acquires its own round (version 1) with the
                    # same compile-check error.
                    var renamed_path := "res://diag_lowering_renamed.gd3"
                    DirAccess.rename_absolute(ProjectSettings.globalize_path(lowering_path),
                            ProjectSettings.globalize_path(renamed_path))
                    service.notify_filesystem_changed()
                    var renamed_read: Dictionary = await _read_text_file(renamed_path)
                    var rename_ok: bool = renamed_read.get("ok", false) == true
                    rename_ok = rename_ok and await _wait_diag_items(service, renamed_path, 1, 60.0)
                    rename_ok = rename_ok and not service.is_diag_version_current(lowering_path, 1)
                    var old_read: Dictionary = await service._rpc_client \
                            .read_file(service.get_diag_module_id(), "/src/diag_lowering.gd3").completed
                    var old_gone: bool = old_read.get("ok", true) == false
                    if old_gone:
                        old_gone = int((old_read.get("error", {}) as Dictionary).get("code", 0)) == -32005
                    _step("rename_reconciles", rename_ok and old_gone,
                            "rename=" + str(rename_ok) + " old_vfs_gone=" + str(old_gone))
                
                    # Deletion triggers a whole-module re-round even with nothing dirty
                    # (review finding): the consumer is clean while the provider exists and must
                    # gain an error once the provider's class_name vanishes from the module.
                    var provider_path := "res://diag_provider.gd3"
                    var provider_src := "class_name DiagProvider\nextends Node\n"
                    var consumer_path := "res://diag_consumer.gd3"
                    var consumer_src := "class_name DiagConsumer\nextends DiagProvider\n"
                    _write_text_file(provider_path, provider_src)
                    _write_text_file(consumer_path, consumer_src)
                    service.notify_filesystem_changed()
                    var dep_ok: bool = await _wait_diag_current(service, consumer_path, 1, 60.0)
                    dep_ok = dep_ok and service.gdcc_diagnostics_for(consumer_path, 1).is_empty()
                    DirAccess.remove_absolute(ProjectSettings.globalize_path(provider_path))
                    service.notify_filesystem_changed()
                    var broke := false
                    var dep_deadline := Time.get_ticks_msec() + 60000
                    while Time.get_ticks_msec() < dep_deadline and not broke:
                        var consumer_items: Array = service.gdcc_diagnostics_for(consumer_path, 1)
                        for item in consumer_items:
                            if str(item.get("message", "")).contains("DiagProvider"):
                                broke = true
                        if not broke:
                            await get_tree().process_frame
                    _step("deletion_triggers_reanalysis", dep_ok and broke,
                            "clean_before=" + str(dep_ok) + " broke_after=" + str(broke))
                
                    # Tombstone rule (review finding): deleting a file whose buffer is dirty must
                    # NOT let the still-open tab resurrect it — `notify_source_changed` refuses
                    # the path while it is absent from disk. The v1 wait first makes the file
                    # mirrored and disk-known, so the deletion is distinguishable from an
                    # unsaved buffer-only script (those stay analyzable).
                    var dirty_del_path := "res://diag_dirty_deleted.gd3"
                    _write_text_file(dirty_del_path, "class_name DiagDirtyDeleted\nextends Node\n")
                    service.notify_filesystem_changed()
                    var dirty_v1_ok: bool = await _wait_diag_current(service, dirty_del_path, 1, 60.0)
                    lang._validate("class_name DiagDirtyDeleted\nextends Node\n", dirty_del_path,
                            true, true, true, true)
                    lang._validate("class_name DiagDirtyDeleted\nextends Node\n# unsaved\n",
                            dirty_del_path, true, true, true, true)
                    # Prove the deletion really happened with a dirty buffer pending: the unsaved
                    # edit must have bumped the content version to 2 (same-text notify is a no-op
                    # that returns the existing version).
                    var dirty_probe: int = service.notify_source_changed(dirty_del_path,
                            "class_name DiagDirtyDeleted\nextends Node\n# unsaved\n")
                    dirty_v1_ok = dirty_v1_ok and dirty_probe == 2
                    DirAccess.remove_absolute(ProjectSettings.globalize_path(dirty_del_path))
                    service.notify_filesystem_changed()
                    var tombstoned := false
                    var tomb_deadline := Time.get_ticks_msec() + 30000
                    while Time.get_ticks_msec() < tomb_deadline and not tombstoned:
                        if service.notify_source_changed(dirty_del_path,
                                "class_name DiagDirtyDeleted\nextends Node\n# unsaved\n") == -1:
                            var ghost_read: Dictionary = await service._rpc_client \
                                    .read_file(service.get_diag_module_id(),
                                            "/src/diag_dirty_deleted.gd3").completed
                            var ghost_gone: bool = ghost_read.get("ok", true) == false
                            if ghost_gone:
                                ghost_gone = int((ghost_read.get("error", {}) as Dictionary)
                                        .get("code", 0)) == -32005
                            tombstoned = ghost_gone
                        if not tombstoned:
                            await get_tree().process_frame
                    _step("delete_with_dirty_buffer", dirty_v1_ok and tombstoned,
                            "v1=" + str(dirty_v1_ok))
                
                    # Outage path: pointing the service at the blackhole endpoint (HTTP 500,
                    # fails fast) must route through ensure_server_hook (recorded here);
                    # restoring the real endpoint recovers.
                    var old_hook: Callable = service.ensure_server_hook
                    _hook_calls = 0
                    service.ensure_server_hook = _record_ensure_hook
                    var hook_probe := "readback_valid=" + str(service.ensure_server_hook.is_valid()) \
                            + " readback_self=" + str(service.ensure_server_hook.get_object() == self) \
                            + " inflight=" + str(service._lifecycle._hook_inflight)
                    service.install(EditorInterface, "127.0.0.1", lsp_port, "127.0.0.1", blackhole_port)
                    var hook_deadline := Time.get_ticks_msec() + 30000
                    while Time.get_ticks_msec() < hook_deadline and _hook_calls == 0:
                        await get_tree().process_frame
                    _step("hook_invoked_on_outage", _hook_calls > 0,
                            "calls=" + str(_hook_calls) + " " + hook_probe
                            + " stage=" + str(service._lifecycle._setup_stage))
                    # Bounded degradation (review finding): an endpoint that accepts TCP (hook
                    # keeps answering OK) but always fails RPC must hit the setup-failure cap
                    # and release busy — the editor must not be pinned awake by a dead endpoint.
                    _hook_calls = 0
                    service.ensure_server_hook = _record_ensure_ok_hook
                    var busy_mark: int = _busy_log.size()
                    var cap_deadline := Time.get_ticks_msec() + 90000
                    var capped := false
                    while Time.get_ticks_msec() < cap_deadline and not capped:
                        await get_tree().process_frame
                        if service._lifecycle._setup_concluded:
                            for i in range(busy_mark, _busy_log.size()):
                                if int(_busy_log[i]) == -1:
                                    capped = true
                    _step("hook_ok_ping_fail_capped", capped,
                            "concluded=" + str(service._lifecycle._setup_concluded)
                            + " calls=" + str(_hook_calls))
                    # Stale hook answers must not be consumed as the newer window's answer
                    # (review finding): retarget while window 1's deferred hook is pending, then
                    # answer it late. The single-outstanding design holds window 2's hook until
                    # the stale answer lands; a wrongly-applied stale OK would set the heartbeat
                    # deadline (reset zeroed it) and suppress window 2's hook entirely.
                    _hook_calls = 0
                    _deferred_replies.clear()
                    service.ensure_server_hook = _record_ensure_defer_hook
                    # Window 1's endpoint must differ from BOTH the blackhole and the real RPC
                    # port (an unchanged endpoint skips the channel reset entirely).
                    var window1_port: int = blackhole_port + 1
                    if window1_port == rpc_port:
                        window1_port = blackhole_port + 2
                    service.install(EditorInterface, "127.0.0.1", lsp_port, "127.0.0.1", window1_port)
                    var defer_deadline := Time.get_ticks_msec() + 30000
                    while Time.get_ticks_msec() < defer_deadline and _deferred_replies.size() < 1:
                        await get_tree().process_frame
                    service.install(EditorInterface, "127.0.0.1", lsp_port, "127.0.0.1", rpc_port)
                    var stale_left_no_trace := false
                    if _deferred_replies.size() >= 1:
                        var stale_reply: Callable = _deferred_replies[0]
                        stale_reply.call(OK, false)
                        # Same-frame check: a wrongly-applied stale OK would have set the
                        # heartbeat deadline to now.
                        stale_left_no_trace = service._lifecycle._next_heartbeat_msec == 0
                    defer_deadline = Time.get_ticks_msec() + 30000
                    while Time.get_ticks_msec() < defer_deadline and _deferred_replies.size() < 2:
                        await get_tree().process_frame
                    var stale_recovered := false
                    if _deferred_replies.size() >= 2:
                        var live_reply: Callable = _deferred_replies[1]
                        live_reply.call(OK, false)
                        stale_recovered = await _wait_diag_ready(service, 45.0)
                    _step("stale_hook_answer_dropped", stale_recovered and stale_left_no_trace,
                            "replies=" + str(_deferred_replies.size())
                            + " no_trace=" + str(stale_left_no_trace))
                    service.ensure_server_hook = old_hook
                    service.install(EditorInterface, "127.0.0.1", lsp_port, "127.0.0.1", rpc_port)
                    var recovered_ok: bool = await _wait_diag_ready(service, 45.0)
                    # Ready alone only proves module.create: require a full post-recovery
                    # analysis round on a mirrored path.
                    recovered_ok = recovered_ok and await _wait_diag_items(service, renamed_path, 1, 60.0)
                    _step("recovered_after_hook", recovered_ok)
                
                    # A hook left pending across uninstall must not poison the attribution
                    # counters (review finding): teardown synthesizes the missing completion, so
                    # the next session's hook answer applies instead of being dropped as stale.
                    _hook_calls = 0
                    _deferred_replies.clear()
                    service.ensure_server_hook = _record_ensure_defer_hook
                    var pending_port: int = blackhole_port + 1
                    if pending_port == rpc_port:
                        pending_port = blackhole_port + 2
                    service.install(EditorInterface, "127.0.0.1", lsp_port, "127.0.0.1", pending_port)
                    var pending_deadline := Time.get_ticks_msec() + 30000
                    while Time.get_ticks_msec() < pending_deadline and _deferred_replies.size() < 1:
                        await get_tree().process_frame
                    # Uninstall with the hook still pending; the deferred reply is NEVER fired
                    # (simulates a launcher teardown dropping its waiter without a callback).
                    service.uninstall()
                    service.install(EditorInterface, "127.0.0.1", lsp_port, "127.0.0.1", rpc_port)
                    # The new window's hook waits out the outstanding one's abandonment
                    # (HOOK_TIMEOUT_MSEC = 30s in the lifecycle) before firing.
                    pending_deadline = Time.get_ticks_msec() + 75000
                    while Time.get_ticks_msec() < pending_deadline and _deferred_replies.size() < 2:
                        await get_tree().process_frame
                    var pending_recovered := false
                    if _deferred_replies.size() >= 2:
                        var new_reply: Callable = _deferred_replies[1]
                        new_reply.call(OK, false)
                        pending_recovered = await _wait_diag_ready(service, 45.0)
                    service.ensure_server_hook = old_hook
                    _step("hook_pending_uninstall_recovers", pending_recovered,
                            "replies=" + str(_deferred_replies.size()))
                
                    for p in [base_path, child_path, renamed_path, dup_a_path, dup_b_path]:
                        DirAccess.remove_absolute(ProjectSettings.globalize_path(p))
                    DirAccess.remove_absolute(ProjectSettings.globalize_path("res://dup_a"))
                    DirAccess.remove_absolute(ProjectSettings.globalize_path("res://dup_b"))
                    _step("survived", true)
                
                func _read_text_file(path: String) -> Dictionary:
                    if not FileAccess.file_exists(path):
                        return {"ok": false}
                    return {"ok": true, "text": FileAccess.get_file_as_string(path)}
                """;
    }

    /// Phase 4 (completion) modes. Kept in a separate text block only because of the 64KB
    /// literal limit — the concatenation must reproduce one continuous GDScript source, so
    /// this block starts with the blank line that separated the sections pre-split.
    private static String driverPluginCompletion() {
        return """
                
                # ---------------- Phase 4: code completion (plan §4.3) ----------------
                
                # The exact safe shape of every `_complete_code` failure path: all four
                # mandatory keys present, result ERR_UNAVAILABLE, empty options.
                func _is_degraded_completion(result: Dictionary) -> bool:
                    var options: Variant = result.get("options", null)
                    return result.get("result", -1) == ERR_UNAVAILABLE \
                            and result.get("force", true) == false \
                            and str(result.get("call_hint", "x")) == "" \
                            and options is Array and (options as Array).is_empty()
                
                # Retries absorb the server's first parse of a freshly synced document and an
                # occasional 400ms budget timeout (which disconnects + re-handshakes per the
                # blocking contract); assertions always run on the final answer.
                func _complete_until_options(lang: ScriptLanguage, code: String, path: String) -> Dictionary:
                    var deadline := Time.get_ticks_msec() + 30000
                    var result: Dictionary = lang._complete_code(code, path, null)
                    var options: Array = result.get("options", [])
                    while options.is_empty() and Time.get_ticks_msec() < deadline:
                        await get_tree().process_frame
                        result = lang._complete_code(code, path, null)
                        options = result.get("options", [])
                    return result
                
                func _options_include(options: Array, display: String) -> bool:
                    for opt in options:
                        if opt is Dictionary and str(opt.get("display", "")) == display:
                            return true
                    return false
                
                func _run_lsp_completion_mode() -> void:
                    var lang := _find_gd3_language()
                    if lang == null:
                        _step("lsp_endpoint_closed", false, "GD3 language not registered")
                        return
                    var port := int(_config["lsp_port"])
                    var closed_port := int(_config["closed_port"])
                    var sentinel := String.chr(0xFFFF)
                    _step("lsp_endpoint_closed", _configure_lsp_port(port) and _steer_lsp_client(closed_port))
                    await get_tree().create_timer(0.5).timeout
                    # Client not READY: the facade must refuse synchronously with the degraded
                    # shape (no blocking spin against a dead endpoint).
                    var deg: Dictionary = lang._complete_code("extends Node\\n\\n" + sentinel, "res://gd3_complete.gd3", null)
                    _step("complete_degraded", _is_degraded_completion(deg), str(deg))
                
                    _step("lsp_endpoint", _steer_lsp_client(port))
                    _step("lsp_ready", await _wait_lsp_ready(45.0))
                
                    # White-box pins: the server decides WHICH kinds it emits, so the mapping
                    # table itself is anchored directly (plan §4.3; Snippet→KIND_NODE_PATH is
                    # the recorded deviation for the server's node-path options).
                    _step("kind_mapping", lang._completion_kind(7) == 0 and lang._completion_kind(2) == 1 \\
                            and lang._completion_kind(3) == 1 and lang._completion_kind(4) == 1 \\
                            and lang._completion_kind(23) == 2 and lang._completion_kind(6) == 3 \\
                            and lang._completion_kind(5) == 3 and lang._completion_kind(10) == 4 \\
                            and lang._completion_kind(13) == 5 and lang._completion_kind(21) == 6 \\
                            and lang._completion_kind(20) == 6 and lang._completion_kind(12) == 6 \\
                            and lang._completion_kind(15) == 7 and lang._completion_kind(17) == 8 \\
                            and lang._completion_kind(19) == 8 and lang._completion_kind(1) == 9 \\
                            and lang._completion_kind(0) == 9 and lang._completion_kind(99) == 9)
                    var it_ok: bool = lang._completion_insert_text({"label": "lab", "textEdit": {"newText": "te"}}, "lab") == "te"
                    it_ok = it_ok and lang._completion_insert_text({"label": "lab", "insertText": "it"}, "lab") == "it"
                    it_ok = it_ok and lang._completion_insert_text({"label": "lab"}, "lab") == "lab"
                    it_ok = it_ok and lang._completion_insert_text({"label": "lab", "insertText": ""}, "lab") == "lab"
                    _step("insert_text_priority", it_ok)
                
                    # Class-body keyword completion behind an identifier prefix: an empty line
                    # yields COMPLETION_NONE (no token sits at the cursor, so the parser never
                    # assigns a context) and legitimately zero options — keywords are offered
                    # only in an identifier context (engine fact, see the plan's Phase 4
                    # deviation note). Every option must carry the seven mandatory keys (a
                    # missing key makes the engine drop the option silently, §2.2; `matches`
                    # stays optional per `op.has("matches")` in script_language_extension.h)
                    # with contract-relevant types pinned (`font_color` Color, location 1024).
                    var body_path := "res://gd3_complete_body.gd3"
                    var body_code := "extends Node\\n\\nf" + sentinel + "\\n"
                    var body_result := await _complete_until_options(lang, body_code, body_path)
                    var body_options: Array = body_result.get("options", [])
                    var shape_ok := not body_options.is_empty()
                    for opt in body_options:
                        if not (opt is Dictionary):
                            shape_ok = false
                            continue
                        for key in ["kind", "display", "insert_text", "font_color", "icon", "default_value", "location"]:
                            if not opt.has(key):
                                shape_ok = false
                        var opt_kind: int = int(opt.get("kind", -1))
                        if opt_kind < 0 or opt_kind > 9:
                            shape_ok = false
                        if not (opt.get("font_color") is Color):
                            shape_ok = false
                        if int(opt.get("location", -1)) != 1024:
                            shape_ok = false
                    _step("complete_keywords",
                            body_result.get("result", -1) == OK and shape_ok \\
                                    and _options_include(body_options, "func"),
                            "options=" + str(body_options.size()) + " shape=" + str(shape_ok))
                
                    # Boundary pair to the degraded negatives: a context-free position (bare
                    # empty line) answers OK with an EMPTY option list — a legitimate empty
                    # result must not collapse into the degraded ERR_UNAVAILABLE shape.
                    var empty_result: Dictionary = lang._complete_code("extends Node\\n\\n" + sentinel + "\\n", body_path, null)
                    var empty_options: Array = empty_result.get("options", [])
                    _step("complete_empty_context",
                            empty_result.get("result", -1) == OK and empty_options.is_empty(), str(empty_result))
                
                    # Member completion after `v.` on an inferred Vector2 local.
                    var member_path := "res://gd3_complete_member.gd3"
                    var member_code := "extends Node\\n\\nfunc f() -> void:\\n    var v := Vector2()\\n    v." + sentinel + "\\n"
                    var member_result := await _complete_until_options(lang, member_code, member_path)
                    var member_options: Array = member_result.get("options", [])
                    _step("complete_member",
                            member_result.get("result", -1) == OK and _options_include(member_options, "x"),
                            "options=" + str(member_options.size()))
                
                    # R11 residual anchor: an emoji sits BEFORE the caret on the same line and
                    # `x` follows it — a UTF-16/byte miscount would push the server-side
                    # sentinel past the `v.` member context and `y` would vanish from the
                    # options (code points are the only correct unit end to end).
                    var cjk_path := "res://gd3_complete_cjk.gd3"
                    var cjk_code := "extends Node\\n\\nfunc f() -> void:\\n    var v := Vector2()\\n    var s := \\"🙂\\" + v." + sentinel + "x\\n"
                    var cjk_result := await _complete_until_options(lang, cjk_code, cjk_path)
                    var cjk_options: Array = cjk_result.get("options", [])
                    _step("complete_cjk",
                            cjk_result.get("result", -1) == OK and _options_include(cjk_options, "y"),
                            "options=" + str(cjk_options.size()))
                
                    # No sentinel in the text: the editor contract is violated, answer degraded.
                    var no_sentinel: Dictionary = lang._complete_code("extends Node\\n", body_path, null)
                    _step("complete_no_sentinel", _is_degraded_completion(no_sentinel), str(no_sentinel))
                
                    # FIFO health on the URI the completion path synced repeatedly (retries
                    # left generations queued, some possibly unanswered): a fresh ERROR sync
                    # must bind to its OWN generation — the watermark wait pops the queued
                    # completion generations first, and the per-generation history must hold
                    # the error diagnostics for exactly this generation. The error sits at
                    # 0-based line 5, a line no earlier document version of this URI used:
                    # severity alone could collide with the stray-`f` error of the completion
                    # sample, so the line is asserted too (review finding).
                    var lsp = _service().get_lsp_client()
                    var fifo_uri: String = lsp.path_to_uri(ProjectSettings.globalize_path(body_path))
                    var fifo_source := "extends Node\\n\\n\\n\\n\\nfunc broken( -> void:\\n    pass\\n"
                    var fg: int = lsp.sync_document(fifo_uri, fifo_source)
                    var fifo_ok := fg > 0
                    if fifo_ok:
                        fifo_ok = lsp.wait_diagnostics(fifo_uri, fg, 15000)
                    if fifo_ok:
                        var bound: Array = lsp.get_diagnostics_for_generation(fifo_uri, fg)
                        var saw_error := false
                        for d in bound:
                            if d is Dictionary and int(d.get("severity", 0)) == 1:
                                var d_range: Dictionary = d.get("range", {})
                                var d_start: Dictionary = d_range.get("start", {})
                                if int(d_start.get("line", -1)) == 5:
                                    saw_error = true
                        fifo_ok = saw_error
                    _step("fifo_survives_completion", fifo_ok, "gen=" + str(fg))
                
                    # Disabled plugin: the resident language instance must keep answering the
                    # safe degraded shape (UNINSTALLED entry-point contract, §3.5).
                    EditorInterface.set_plugin_enabled("gdcc", false)
                    await get_tree().process_frame
                    await get_tree().process_frame
                    var resident: ScriptLanguage = _service().get_language_instance()
                    var dis: Dictionary = resident._complete_code("extends Node\\n" + sentinel, body_path, null)
                    EditorInterface.set_plugin_enabled("gdcc", true)
                    await get_tree().process_frame
                    await get_tree().process_frame
                    _step("complete_disabled_safe", _is_degraded_completion(dis), str(dis))
                
                    # Post-cycle sanity: the language-level `_validate` still surfaces LSP
                    # errors on a fresh URI after a disable/enable that followed completion
                    # traffic (the cycle itself disconnects and re-handshakes the client; the
                    # retry window absorbs the reconnect).
                    var health_path := "res://gd3_complete_health.gd3"
                    var health_source := "extends Node\\n\\nfunc broken( -> void:\\n    pass\\n"
                    _write_text_file(health_path, health_source)
                    var health_lang := _find_gd3_language()
                    var health_ok := false
                    if health_lang != null:
                        var health_result := await _validate_until_errors(health_lang, health_source, health_path)
                        health_ok = not (health_result.get("errors", []) as Array).is_empty()
                    _step("validate_still_healthy", health_ok)
                
                    for p in [body_path, member_path, cjk_path, health_path]:
                        DirAccess.remove_absolute(ProjectSettings.globalize_path(p))
                    _step("survived", true)
                """;
    }

    /// Phase 5 (diagnostic visibility) mode. Kept in a separate text block only because of
    /// the 64KB literal limit — the concatenation must reproduce one continuous GDScript
    /// source, so this block starts with the blank line that separated the sections.
    private static String driverPluginRevalidate() {
        return """
                
                # ---------------- Phase 5: diagnostic visibility (plan §7 Phase 5) ----------------
                
                # Observation anchor: the language's guard-passing `_validate` invocation count.
                # The acceptance chain is "merge → pump → plugin relay → ScriptTextEditor
                # ._validate_script → language._validate", so a post-merge counter advance with
                # no driver-side validation call IS the revalidation. (Editor-signal-level
                # observation via CodeTextEditor.validate_script connections proved unreliable
                # for relay-originated emissions in this nested context; the counter reads the
                # production effect directly and is what the plan's acceptance names.)
                func _lang_validate_count() -> int:
                    var l := _find_gd3_language()
                    if l == null:
                        return -1
                    return l.validate_call_count()
                
                func _wait_validate_advance(baseline: int, max_frames: int) -> bool:
                    var frames := 0
                    while _lang_validate_count() <= baseline and frames < max_frames:
                        await get_tree().process_frame
                        frames += 1
                    return _lang_validate_count() > baseline
                
                # The editor idle beat (one-shot per text change: 1.5s clean / 0.5s once errors
                # are displayed — `set_error_count` switches the cadence, 4.5 code_editor.cpp)
                # also advances the counter. The windows stay unambiguous per direction:
                # errors-present changes have their idle (0.5s) strictly before the merge
                # (≥0.8s debounce), so a detection-time baseline is post-idle; the clean→error
                # step instead settles PAST the 1.5s idle first (its beat lands after the merge
                # otherwise and would false-pass the window), letting that idle validation push
                # the version itself.
                var _last_text_change_msec: int = 0
                
                func _settle_past_idle() -> void:
                    var deadline := Time.get_ticks_msec() + 15000
                    while Time.get_ticks_msec() - _last_text_change_msec < 1800 \\
                            and Time.get_ticks_msec() < deadline:
                        await get_tree().process_frame
                
                # Locates the gdcc plugin's dock: editor plugins are siblings of this driver
                # under the same parent, and the gdcc plugin keeps the dock in `_dock`.
                func _find_gdcc_dock() -> Variant:
                    var parent := get_parent()
                    if parent == null:
                        return null
                    for sib in parent.get_children():
                        if sib == self or not (sib is EditorPlugin):
                            continue
                        var sib_script: Script = sib.get_script()
                        if sib_script != null and str(sib_script.resource_path).ends_with("addons/gdcc/plugin.gd"):
                            return sib.get("_dock")
                    return null

                # Phase 10: dock Compile copies the auto-synced diagnostics module server-side
                # and compiles the copy; the diagnostics module gate stays free mid-compile.
                func _run_dock_compile_mode() -> void:
                    var service := _service()
                    var dock: Variant = _find_gdcc_dock()
                    if service == null or dock == null:
                        _step("service_ready", false, "GdccEditorService or dock missing")
                        return
                    var lsp_port := int(_config["lsp_port"])
                    var rpc_port := int(_config["rpc_port"])
                    _step("service_ready", service.install(EditorInterface, "127.0.0.1", lsp_port, "127.0.0.1", rpc_port) == OK)

                    var fixture_path := "res://compile_probe.gd3"
                    var fixture_src := "class_name DockCompileProbe\\nextends Node\\n"
                    _write_text_file(fixture_path, fixture_src)
                    service.notify_filesystem_changed()
                    _step("fixture_written", true)

                    # Driver-owned client: observes sync/copy/compile state straight from the
                    # server, independent of the dock's own client.
                    var client := GdccRpcClient.new()
                    add_child(client)
                    client.host = "127.0.0.1"
                    client.port = rpc_port

                    var ready_ok: bool = await _wait_diag_ready(service, 45.0)
                    var diag_module_id: String = service.get_diag_module_id()

                    # The addon project ships res://src/test2.gd3 — a deliberately broken
                    # sample (parameterized _init, bare print()) whose frontend errors would
                    # block any whole-workspace compile. Remove it from this case's copy and
                    # wait for the delete to reconcile into the server VFS. The delete can
                    # race the INITIAL sync wave (the file may be uploaded only after we
                    # removed it from disk), so first wait for the file to appear, then for
                    # the delete to land.
                    var present := false
                    var present_deadline := Time.get_ticks_msec() + 30000
                    while Time.get_ticks_msec() < present_deadline and not present:
                        var before: Dictionary = await client.read_file(diag_module_id, "/src/src/test2.gd3").completed
                        present = before["ok"]
                        if not present:
                            await get_tree().create_timer(0.2).timeout
                    DirAccess.remove_absolute(ProjectSettings.globalize_path("res://src/test2.gd3"))
                    service.notify_filesystem_changed()
                    var removed := false
                    var remove_deadline := Time.get_ticks_msec() + 30000
                    while Time.get_ticks_msec() < remove_deadline and not removed:
                        var stale: Dictionary = await client.read_file(diag_module_id, "/src/src/test2.gd3").completed
                        removed = not stale["ok"]
                        if not removed:
                            await get_tree().create_timer(0.2).timeout
                    _step("broken_fixture_removed", present and removed)
                    if not (present and removed):
                        return

                    # res://compile_probe.gd3 mirrors to /src/compile_probe.gd3 (service VFS
                    # mapping contract); poll until the content round-trips verbatim.
                    var synced := false
                    var sync_deadline := Time.get_ticks_msec() + 30000
                    while Time.get_ticks_msec() < sync_deadline and not synced:
                        var read: Dictionary = await client.read_file(diag_module_id, "/src/compile_probe.gd3").completed
                        synced = read["ok"] and str(read["result"]) == fixture_src
                        if not synced:
                            await get_tree().create_timer(0.2).timeout
                    _step("fixture_synced", ready_ok and synced)
                    if not (ready_ok and synced):
                        return

                    # Point the dock at the test server and press its real Compile button.
                    dock._host_input.text = "127.0.0.1"
                    dock._port_input.text = str(rpc_port)
                    var compile_button: Button = null
                    for button in dock._action_buttons:
                        if button.text == "Compile":
                            compile_button = button
                    if compile_button == null:
                        _step("copy_created", false, "Compile button not found in the dock")
                        return
                    compile_button.pressed.emit()
                    # Same id derivation as the dock: diagnostics prefix swapped for the
                    # compile prefix (pid-scoped per editor process).
                    var copy_module_id: String = diag_module_id.replace(
                            "gdcc_editor_diagnostics_", "gdcc_editor_compile_")
                    # module.get would block server-side behind the copy's compile (the
                    # module gate is held for the whole native build); module.list is the
                    # gate-free channel for observing the registration.
                    var copy_seen := false
                    var copy_deadline := Time.get_ticks_msec() + 30000
                    while Time.get_ticks_msec() < copy_deadline and not copy_seen:
                        var listed: Dictionary = await client.call_rpc("module.list", {}).completed
                        if listed["ok"]:
                            for entry in listed["result"]:
                                if str(entry["moduleId"]) == copy_module_id:
                                    copy_seen = true
                        if not copy_seen:
                            await get_tree().create_timer(0.2).timeout
                    _step("copy_created", copy_seen)
                    if not copy_seen:
                        print("DOCK LOG:\n" + dock._log_output.text)
                        return

                    # While the dock knows an active compile task on the copy, an analyze on
                    # the DIAGNOSTICS module must still answer fast: a compile holding the
                    # diag gate would delay it by the remaining native build (seconds even
                    # when warm), while a healthy loopback round-trip is subsecond.
                    var task_deadline := Time.get_ticks_msec() + 30000
                    while int(dock._current_task_id) <= 0 and Time.get_ticks_msec() < task_deadline:
                        await get_tree().process_frame
                    var first_task: int = int(dock._current_task_id)
                    var analyze_start := Time.get_ticks_msec()
                    var analyzed: Dictionary = await client.analyze(diag_module_id, false).completed
                    var analyze_elapsed := Time.get_ticks_msec() - analyze_start
                    # Strong anchor: the analyze answered while the copy's compile was still in
                    # flight. A regression that made compile hold the DIAGNOSTICS module gate
                    # would let analyze return only after the compile finished (terminal here).
                    var task_view: Dictionary = await client.get_compile_task(first_task).completed
                    var task_state := str(task_view["result"]["state"]) if task_view["ok"] else "?"
                    var diag_alive: bool = first_task > 0 and analyzed["ok"] \
                            and str(analyzed["result"]["outcome"]) == "COMPLETED" \
                            and (task_state == "QUEUED" or task_state == "RUNNING")
                    _step("diag_alive_during_compile", diag_alive,
                            "task_state=" + task_state + " elapsed_ms=" + str(analyze_elapsed))

                    var last_result := {}
                    var succeeded := false
                    var result_deadline := Time.get_ticks_msec() + 240000
                    while Time.get_ticks_msec() < result_deadline and not succeeded:
                        var polled: Dictionary = await client.get_last_compile_result(copy_module_id).completed
                        if polled["ok"] and polled["result"] != null:
                            last_result = polled["result"]
                            succeeded = str(last_result.get("outcome", "")) == "SUCCESS"
                        if not succeeded:
                            await get_tree().create_timer(0.5).timeout
                    # The copy built into its own .godot/gdcc/<copy> host dir.
                    var build_dir: String = ProjectSettings.globalize_path("res://.godot/gdcc/" + copy_module_id)
                    succeeded = succeeded and DirAccess.dir_exists_absolute(build_dir) \
                            and (DirAccess.get_directories_at(build_dir).size() \
                            + DirAccess.get_files_at(build_dir).size()) > 0
                    _step("compile_succeeded", succeeded, str(last_result))
                    if not succeeded:
                        print("DOCK LOG:\n" + dock._log_output.text)
                        return

                    # Second compile through the real UI path: wait until the dock drained the
                    # first task (button re-enabled), CHANGE the fixture, and wait for the
                    # re-sync — the re-created copy must carry the new content, proving the
                    # -32001 delete+recopy path rather than a stale-copy reuse.
                    var idle_deadline := Time.get_ticks_msec() + 30000
                    while compile_button.disabled and Time.get_ticks_msec() < idle_deadline:
                        await get_tree().process_frame
                    var updated_src := "class_name DockCompileProbe\\nextends Node\\n\\n# recompiled\\n"
                    _write_text_file(fixture_path, updated_src)
                    service.notify_filesystem_changed()
                    var resynced := false
                    var resync_deadline := Time.get_ticks_msec() + 30000
                    while Time.get_ticks_msec() < resync_deadline and not resynced:
                        var reread: Dictionary = await client.read_file(diag_module_id, "/src/compile_probe.gd3").completed
                        resynced = reread["ok"] and str(reread["result"]) == updated_src
                        if not resynced:
                            await get_tree().create_timer(0.2).timeout
                    _step("fixture_resynced", not compile_button.disabled and resynced)
                    if not resynced:
                        return
                    compile_button.pressed.emit()
                    # Waiting for the new task id first pins the ordering: anything observed
                    # afterwards belongs to the re-copied module, never to the pre-delete one.
                    var second_task: int = -1
                    var second_start_deadline := Time.get_ticks_msec() + 30000
                    while Time.get_ticks_msec() < second_start_deadline and second_task < 0:
                        var current: int = int(dock._current_task_id)
                        if current > first_task:
                            second_task = current
                        await get_tree().process_frame
                    var second_ok: bool = second_task > 0
                    var second_deadline := Time.get_ticks_msec() + 240000
                    var second_success := false
                    while Time.get_ticks_msec() < second_deadline and not second_success:
                        var polled2: Dictionary = await client.get_last_compile_result(copy_module_id).completed
                        if polled2["ok"] and polled2["result"] != null:
                            second_success = str(polled2["result"].get("outcome", "")) == "SUCCESS"
                        if not second_success:
                            await get_tree().create_timer(0.5).timeout
                    # Post-success content check (read_file blocks behind the copy's module
                    # gate while the compile runs, so it only answers afterwards): the
                    # re-created copy must carry the UPDATED source. With a stale-copy reuse
                    # bug the old content would survive; with a skipped recopy the id would
                    # still hold the pre-edit bytes.
                    var fresh_copy := false
                    if second_success:
                        var copy_read: Dictionary = await client.read_file(copy_module_id, "/src/compile_probe.gd3").completed
                        fresh_copy = copy_read["ok"] and str(copy_read["result"]) == updated_src
                    _step("recompile_replaces_copy", second_ok and fresh_copy and second_success,
                            "first_task=" + str(first_task) + " second_task=" + str(second_task)
                            + " fresh_copy=" + str(fresh_copy))
                    if not (second_ok and fresh_copy and second_success):
                        print("DOCK LOG:\n" + dock._log_output.text)
                        return

                    var final_analyze: Dictionary = await client.analyze(diag_module_id, false).completed
                    _step("diag_still_responsive", final_analyze["ok"]
                            and str(final_analyze["result"]["outcome"]) == "COMPLETED")
                    _step("survived", true)

                func _run_diag_revalidate_mode() -> void:
                    var service := _service()
                    var lang := _find_gd3_language()
                    if service == null or lang == null:
                        _step("service_ready", false, "GdccEditorService or GD3 language missing")
                        return
                    var lsp_port := int(_config["lsp_port"])
                    var rpc_port := int(_config["rpc_port"])
                    var closed_port := int(_config["closed_port"])
                    _step("service_ready", service.install(EditorInterface, "127.0.0.1", lsp_port, "127.0.0.1", rpc_port) == OK)
                
                    var base_path := "res://rv_base.gd"
                    var target_path := "res://rv_target.gd3"
                    var other_path := "res://rv_other.gd3"
                    var clean_src := "class_name RvTarget\\nextends Node\\n"
                    # Same gdcc-only diagnostic family as `gdcc_diag` (path-based extends of a
                    # .gd): legal GDScript (the LSP stays silent), rejected by gdcc.
                    var err_src := "class_name RvTarget\\nextends \\"res://rv_base.gd\\"\\n"
                    _write_text_file(base_path, "extends Node\\n")
                    _write_text_file(target_path, clean_src)
                    _write_text_file(other_path, "class_name RvOther\\nextends Node\\n")
                    service.notify_filesystem_changed()
                    _step("fixtures_written", true)
                
                    # Clean round: both fixtures analyzed at version 1 (clean rounds serve EMPTY
                    # diagnostics, so the merge is observed via the version-freshness probe).
                    var analysis_ok: bool = await _wait_diag_ready(service, 45.0)
                    analysis_ok = analysis_ok and await _wait_diag_current(service, target_path, 1, 60.0)
                    analysis_ok = analysis_ok and await _wait_diag_current(service, other_path, 1, 60.0)
                    _step("analysis_ready", analysis_ok)
                
                    # Open the target in the script editor — revalidation only ever fires on the
                    # CURRENT tab. The enable-validation on open sees the clean v1 buffer (== the
                    # reconciled registry text), so it creates no new version and no merge.
                    # 4.5: get_base_editor() IS the CodeEdit (the validate_script signal lives on
                    # its CodeTextEditor parent; the plugin's relay resolves that wrapper).
                    EditorInterface.set_main_screen_editor("Script")
                    var target_res: Resource = ResourceLoader.load(target_path)
                    if target_res != null:
                        EditorInterface.edit_resource(target_res)
                    var code_edit: CodeEdit = null
                    var probe := "res=" + str(target_res != null)
                    var open_deadline := Time.get_ticks_msec() + 15000
                    while Time.get_ticks_msec() < open_deadline and code_edit == null:
                        await get_tree().process_frame
                        var se := EditorInterface.get_script_editor()
                        var cur := se.get_current_editor()
                        var cur_script := se.get_current_script()
                        if cur != null and cur_script != null and cur_script.resource_path == target_path:
                            var candidate: Control = cur.get_base_editor()
                            probe = "cand=" + str(candidate != null)
                            if candidate is CodeEdit:
                                code_edit = candidate as CodeEdit
                    _step("editor_opened", code_edit != null, probe)
                
                    # set_text models the user's edit. The counter baseline must be read only
                    # AFTER the editor's own idle beat for that change has landed: in the
                    # clean→error direction the idle delay is 1.5s (set_error_count switches to
                    # the 0.5s with-errors cadence only once errors are displayed, 4.5
                    # code_editor.cpp:1733-1737) while the analysis debounce is 0.8s, so the
                    # merge can land BEFORE the idle — a post-merge counter window would then
                    # pass on the idle beat alone (review finding). Settling first also lets
                    # the idle validation itself push version 2 (no driver-side push needed).
                    if code_edit != null:
                        code_edit.set_text(err_src)
                        _last_text_change_msec = Time.get_ticks_msec()
                    await _settle_past_idle()
                    var pushed_v2: bool = service.registry().live_version(target_path) == 2
                    var merged: bool = pushed_v2 and await _wait_diag_current(service, target_path, 2, 60.0)
                    var fired := false
                    if merged:
                        fired = await _wait_validate_advance(_lang_validate_count(), 45)
                    # Content anchor (driver-side, after the signal): the v2 cache serves the
                    # gdcc error that the signal-triggered validation displayed.
                    var shown_result: Dictionary = lang._validate(err_src, target_path, true, true, true, true)
                    var gdcc_shown := false
                    for e in shown_result.get("errors", []):
                        if str(e.get("message", "")).begins_with("[gdcc sema.class_skeleton]"):
                            gdcc_shown = true
                    _step("signal_revalidates", merged and fired and gdcc_shown,
                            "merged=" + str(merged) + " fired=" + str(fired)
                            + " shown=" + str(gdcc_shown))
                
                    # Same-version re-merge must NOT revalidate: nothing became newly readable.
                    # Settle past the previous change's idle beat first so only the relay could
                    # move the counter in this window.
                    await _settle_past_idle()
                    var round_before: int = service.scheduler().last_round_msec()
                    var quiet_baseline := _lang_validate_count()
                    service.scheduler().request_full_reanalysis()
                    var reround := false
                    var reround_deadline := Time.get_ticks_msec() + 30000
                    while Time.get_ticks_msec() < reround_deadline and not reround:
                        reround = service.scheduler().last_round_msec() != round_before
                        await get_tree().process_frame
                    var quiet_frames := 0
                    while quiet_frames < 30:
                        await get_tree().process_frame
                        quiet_frames += 1
                    _step("same_version_remerge_quiet", reround
                            and _lang_validate_count() == quiet_baseline,
                            "reround=" + str(reround))
                
                    # Version advance with an UNCHANGED diagnostic payload must revalidate
                    # (a pre-merge validation of v3 necessarily read an empty cache).
                    var err_src_v3 := err_src + "# same diagnostic, new version\\n"
                    if code_edit != null:
                        code_edit.set_text(err_src_v3)
                        _last_text_change_msec = Time.get_ticks_msec()
                    lang._validate(err_src_v3, target_path, true, true, true, true)
                    var merged_v3: bool = await _wait_diag_current(service, target_path, 3, 60.0)
                    var fired_v3 := false
                    if merged_v3:
                        fired_v3 = await _wait_validate_advance(_lang_validate_count(), 45)
                    _step("new_version_same_diag_fires", merged_v3 and fired_v3,
                            "merged=" + str(merged_v3) + " fired=" + str(fired_v3))
                
                    # A merge covering a NON-current path must not revalidate anything (only the
                    # current tab's script is revalidated): rv_target stays the current tab
                    # while rv_other gets a fresh background version. (Driving this through a
                    # tab switch would be self-defeating: leaving a dirty tab makes the editor
                    # re-validate its buffer, which bumps the registry and confuses versions.)
                    # No text change happens here, so no idle beat can interfere.
                    var baseline_vs := _lang_validate_count()
                    var v_other: int = service.notify_source_changed(other_path,
                            "class_name RvOther\\nextends Node\\n# background round\\n")
                    var merged_other: bool = v_other > 0 \\
                            and await _wait_diag_current(service, other_path, v_other, 60.0)
                    var drop_frames := 0
                    while drop_frames < 30:
                        await get_tree().process_frame
                        drop_frames += 1
                    _step("non_current_tab_dropped", merged_other
                            and _lang_validate_count() == baseline_vs,
                            "merged=" + str(merged_other))
                
                    # Popup deferral (rv_target is still the current tab). The buffer is set to
                    # the SAME text the new registry version records, so the re-sent validation
                    # displays the merged diagnostics instead of reading an empty cache (review
                    # finding). The caret must sit on a word base matching the probe option: the
                    # filter cancels the popup outright when the completion base is empty (4.5
                    # code_edit.cpp `_filter_code_completion_candidates_impl`) and drops options
                    # that are not subsequence matches of the base.
                    var popup_src := err_src + "# popup deferred\\n"
                    if code_edit != null:
                        code_edit.set_text(popup_src)
                        _last_text_change_msec = Time.get_ticks_msec()
                    lang._validate(popup_src, target_path, true, true, true, true)
                    var v_popup: int = service.registry().live_version(target_path)
                    var popup_ok := false
                    if code_edit != null:
                        code_edit.set_caret_line(0)
                        code_edit.set_caret_column("class_name RvTarget".length())
                        code_edit.add_code_completion_option(9, "RvTargetProbe", "RvTargetProbe", Color(1, 1, 1))
                        code_edit.update_code_completion_options(true)
                        popup_ok = not code_edit.get_code_completion_options().is_empty()
                    var merged_popup := false
                    if popup_ok and v_popup > 0:
                        merged_popup = await _wait_diag_current(service, target_path, v_popup, 60.0)
                    # The merge landed while the popup is open: the relay must defer, so the
                    # counter stays flat through this window.
                    var defer_baseline := _lang_validate_count()
                    var defer_frames := 0
                    while defer_frames < 30:
                        await get_tree().process_frame
                        defer_frames += 1
                    var deferred: bool = _lang_validate_count() == defer_baseline
                    var resent := false
                    if deferred and code_edit != null:
                        code_edit.cancel_code_completion()
                        resent = await _wait_validate_advance(defer_baseline, 60)
                    # Content anchor: the re-sent validation's cache version equals the buffer
                    # version, so the deferred diagnostics are actually displayable.
                    var popup_shown := false
                    if resent:
                        var popup_result: Dictionary = lang._validate(popup_src, target_path, true, true, true, true)
                        for e in popup_result.get("errors", []):
                            if str(e.get("message", "")).begins_with("[gdcc sema.class_skeleton]"):
                                popup_shown = true
                    _step("popup_deferral", popup_ok and merged_popup and deferred and resent
                            and popup_shown,
                            "popup=" + str(popup_ok) + " merged=" + str(merged_popup)
                            + " deferred=" + str(deferred) + " resent=" + str(resent)
                            + " shown=" + str(popup_shown))
                
                    # Endpoint retarget through the DOCK commit path (item 3): the settings
                    # write handler and the service commit handler are the real production
                    # entry points; a dead endpoint must reset the channel and surface a
                    # failure reason, a live one must recover READY.
                    var dock: Variant = _find_gdcc_dock()
                    var closed_ok := false
                    if dock != null:
                        dock.get("_port_input").text = str(closed_port)
                        dock.call("_on_port_changed", str(closed_port))
                        dock.call("_on_endpoint_committed")
                        closed_ok = service.rpc_port() == closed_port \\
                                and int(EditorInterface.get_editor_settings() \\
                                .get_setting("gdcc/server/port")) == closed_port
                    var failure_seen := false
                    var fail_deadline := Time.get_ticks_msec() + 20000
                    while closed_ok and Time.get_ticks_msec() < fail_deadline and not failure_seen:
                        var st: Dictionary = service.diag_channel_status()
                        if str(st.get("last_failure", "")) != "" and not service.is_diag_ready():
                            failure_seen = true
                        await get_tree().process_frame
                    var status_closed := false
                    var status_deadline := Time.get_ticks_msec() + 8000
                    while failure_seen and Time.get_ticks_msec() < status_deadline and not status_closed:
                        var closed_text: String = dock.get("_status_label").text
                        status_closed = closed_text.contains(str(closed_port)) \\
                                and closed_text.contains("not ready") \\
                                and not closed_text.contains("Last failure: none")
                        await get_tree().process_frame
                    _step("endpoint_retarget_closed", closed_ok and failure_seen and status_closed,
                            "retargeted=" + str(closed_ok) + " failure=" + str(failure_seen)
                            + " status=" + str(status_closed))
                
                    var recovered := false
                    if dock != null:
                        dock.get("_port_input").text = str(rpc_port)
                        dock.call("_on_port_changed", str(rpc_port))
                        dock.call("_on_endpoint_committed")
                        recovered = service.rpc_port() == rpc_port and await _wait_diag_ready(service, 45.0)
                    var status_ready := false
                    var ready_text_deadline := Time.get_ticks_msec() + 8000
                    while recovered and Time.get_ticks_msec() < ready_text_deadline and not status_ready:
                        var ready_text: String = dock.get("_status_label").text
                        status_ready = ready_text.contains(str(rpc_port)) \\
                                and ready_text.contains("ready") and not ready_text.contains("not ready")
                        await get_tree().process_frame
                    _step("endpoint_retarget_recover", recovered and status_ready,
                            "recovered=" + str(recovered) + " status=" + str(status_ready))
                
                    # Single-sourcing across a plugin cycle (item 3): install() must read the
                    # committed EditorSettings pair, so the re-enabled plugin lands on the same
                    # endpoint without any dock interaction.
                    EditorInterface.set_plugin_enabled("gdcc", false)
                    await get_tree().process_frame
                    await get_tree().process_frame
                    EditorInterface.set_plugin_enabled("gdcc", true)
                    await get_tree().process_frame
                    await get_tree().process_frame
                    var service2 := _service()
                    var persisted := false
                    if service2 != null:
                        persisted = service2.rpc_port() == rpc_port
                    var ready_after: bool = persisted and await _wait_diag_ready(service2, 45.0)
                    _step("endpoint_persists_after_toggle", ready_after,
                            "port=" + (str(service2.rpc_port()) if service2 != null else "n/a"))
                
                    # The dock is recreated by the re-enable: re-locate it and confirm the
                    # status area reflects the recovered channel (endpoint + ready + a
                    # completed analysis round).
                    var dock2: Variant = _find_gdcc_dock()
                    var status_final := false
                    var final_deadline := Time.get_ticks_msec() + 60000
                    while dock2 != null and Time.get_ticks_msec() < final_deadline and not status_final:
                        var final_text: String = dock2.get("_status_label").text
                        var st2: Dictionary = service2.diag_channel_status()
                        status_final = final_text.contains(str(rpc_port)) \\
                                and final_text.contains("ready") and not final_text.contains("not ready") \\
                                and int(st2.get("last_round_msec", 0)) > 0
                        await get_tree().process_frame
                    _step("status_area", dock2 != null and status_final)
                
                    for p in [base_path, target_path, other_path]:
                        DirAccess.remove_absolute(ProjectSettings.globalize_path(p))
                    _step("survived", true)
                """;
    }

    /// Phase 6+7 (class metadata + class_name erasure) mode. Separate text block for the
    /// 64KB literal limit, same as the completion/revalidate chunks.
    private static String driverPluginClassMetadata() {
        return """
                
                # ---------------- Phase 6+7: class metadata + class_name erasure (plan §7) ----------------
                
                func _run_class_name_erasure_mode() -> void:
                    var service := _service()
                    var lang := _find_gd3_language()
                    if service == null or lang == null:
                        _step("service_ready", false, "GdccEditorService or GD3 language missing")
                        return
                    var lsp_port := int(_config["lsp_port"])
                    var rpc_port := int(_config["rpc_port"])
                    _step("service_ready", service.install(EditorInterface, "127.0.0.1", lsp_port, "127.0.0.1", rpc_port) == OK)
                    _step("lsp_ready", await _wait_lsp_ready(30.0))
                
                    var subject_path := "res://src/phase7_subject.gd3"
                    var subject_src := "class_name Gd3Phase7Subject\\nextends Node\\n\\nclass Inner extends RefCounted:\\n    pass\\n"
                    var subject_abs := ProjectSettings.globalize_path(subject_path)
                
                    # --- Phase 6: metadata readback through ClassDB (the runtime consumption
                    # contract): the compiled fixture class answers `_gdcc_get_metadata` with the
                    # exact source facts the compile was fed. ---
                    var meta_ok: bool = ClassDB.class_exists("Gd3Phase7Subject") \\
                            and ClassDB.class_get_api_type("Gd3Phase7Subject") == 2 \\
                            and ClassDB.class_has_method("Gd3Phase7Subject", "_gdcc_get_metadata")
                    var meta: Variant = null
                    if meta_ok:
                        meta = ClassDB.class_call_static("Gd3Phase7Subject", "_gdcc_get_metadata")
                    var gdcc: Dictionary = {}
                    if meta is Dictionary:
                        gdcc = meta.get("gdcc", {})
                    if meta is Dictionary and not gdcc.is_empty():
                        meta_ok = meta_ok and int(gdcc.get("format", -1)) == 1 \\
                                and str(gdcc.get("source_res_path", "")) == subject_path \\
                                and str(gdcc.get("source_path", "")) == subject_abs \\
                                and str(gdcc.get("source_name", "")) == "Gd3Phase7Subject" \\
                                and str(gdcc.get("module", "")) == "Gd3 Phase7 Fixture" \\
                                and str(gdcc.get("compiled_at", "")) != "" \\
                                and str(gdcc.get("version", "")) != ""
                    else:
                        meta_ok = false
                    _step("metadata_readback", meta_ok, str(gdcc))
                
                    # Nested class metadata (canonical registration name, dotted source name) and
                    # cache isolation: mutating one returned Dictionary must not leak into the
                    # next call's answer (the C side returns a deep copy of its parse cache).
                    var inner_meta: Variant = ClassDB.class_call_static("Gd3Phase7Subject__sub__Inner", "_gdcc_get_metadata")
                    var inner_ok: bool = inner_meta is Dictionary \\
                            and str(inner_meta.get("gdcc", {}).get("source_name", "")) == "Gd3Phase7Subject.Inner" \\
                            and str(inner_meta.get("gdcc", {}).get("source_res_path", "")) == subject_path
                    if meta is Dictionary:
                        meta["gdcc"]["injected"] = true
                        var again: Variant = ClassDB.class_call_static("Gd3Phase7Subject", "_gdcc_get_metadata")
                        inner_ok = inner_ok and again is Dictionary and not again.get("gdcc", {}).has("injected")
                    _step("metadata_inner_and_cache", inner_ok)
                
                    # --- Phase 7 admission matrix (whitebox on lsp_safe_text): only the exact
                    # provenance match erases; every other shape stays identical text. ---
                    var matrix_ok := true
                    var v_positive: Dictionary = service.lsp_safe_text(subject_path, subject_src)
                    matrix_ok = matrix_ok and v_positive.get("erased", false) == true \\
                            and not str(v_positive["text"]).contains("class_name")
                    var v_wrong_path: Dictionary = service.lsp_safe_text("res://src/elsewhere.gd3", subject_src)
                    matrix_ok = matrix_ok and v_wrong_path.get("erased", true) == false \\
                            and str(v_wrong_path["text"]) == subject_src
                    var v_core: Dictionary = service.lsp_safe_text(subject_path, "class_name Node\\nextends RefCounted\\n")
                    matrix_ok = matrix_ok and v_core.get("erased", true) == false
                    var v_uncompiled: Dictionary = service.lsp_safe_text(subject_path, "class_name Gd3NoSuchClass9001\\nextends Node\\n")
                    matrix_ok = matrix_ok and v_uncompiled.get("erased", true) == false
                    var v_bad_base: Dictionary = service.lsp_safe_text(subject_path, "class_name Gd3Phase7Subject\\nextends RefCounted\\n")
                    matrix_ok = matrix_ok and v_bad_base.get("erased", true) == false
                    var v_no_provenance: Dictionary = service.lsp_safe_text(subject_path, "class_name GdccScriptLanguage\\nextends ScriptLanguageExtension\\n")
                    matrix_ok = matrix_ok and v_no_provenance.get("erased", true) == false
                    var v_no_header: Dictionary = service.lsp_safe_text(subject_path, "extends Node\\n")
                    matrix_ok = matrix_ok and v_no_header.get("erased", true) == false \\
                            and str(v_no_header["text"]) == "extends Node\\n"
                    _step("admission_matrix", matrix_ok)
                
                    # --- Rewrite forms, bidirectional mapping (code points), and scanner traps. ---
                    var forms_ok := true
                    var f_blank_lines: PackedStringArray = str(v_positive["text"]).split("\\n")
                    forms_ok = forms_ok and f_blank_lines.size() == subject_src.split("\\n").size() \\
                            and f_blank_lines[0] == " ".repeat("class_name Gd3Phase7Subject".length()) \\
                            and int(v_positive["erase_line"]) == 0 and int(v_positive["removed_prefix"]) == 0
                    var same_src := "class_name Gd3Phase7Subject extends Node\\npass\\n"
                    var f_same: Dictionary = service.lsp_safe_text(subject_path, same_src)
                    var f_same_lines: PackedStringArray = str(f_same["text"]).split("\\n")
                    forms_ok = forms_ok and f_same.get("erased", false) == true \\
                            and f_same_lines[0] == "extends Node" and f_same_lines[1] == "pass" \\
                            and int(f_same["erase_line"]) == 0 and int(f_same["removed_prefix"]) == 28
                    var fwd: Dictionary = service.lsp_safe_forward_position(f_same, 0, 30)
                    forms_ok = forms_ok and int(fwd["line"]) == 0 and int(fwd["character"]) == 2
                    forms_ok = forms_ok and service.lsp_safe_reverse_column(f_same, 0, 2) == 30
                    forms_ok = forms_ok and service.lsp_safe_reverse_column(f_same, 1, 3) == 3
                    var fwd_other: Dictionary = service.lsp_safe_forward_position(f_same, 1, 4)
                    forms_ok = forms_ok and int(fwd_other["line"]) == 1 and int(fwd_other["character"]) == 4
                    # CJK text before the header: the scan and the rewrite count code points, so the
                    # erased line index and content must be unaffected by multibyte characters.
                    var cjk_src := "var a = \\"中文\\"\\nclass_name Gd3Phase7Subject extends Node\\n"
                    var f_cjk: Dictionary = service.lsp_safe_text(subject_path, cjk_src)
                    forms_ok = forms_ok and f_cjk.get("erased", false) == true \\
                            and int(f_cjk["erase_line"]) == 1 \\
                            and str(f_cjk["text"]).split("\\n")[1] == "extends Node"
                    # Traps (R25): comment lines, multiline-string content, and indented headers
                    # must never match.
                    var t_comment: Dictionary = service.lsp_safe_text(subject_path, "# class_name Gd3Phase7Subject\\nextends Node\\n")
                    forms_ok = forms_ok and t_comment.get("erased", true) == false
                    var dq := String.chr(34)
                    var dq3 := dq + dq + dq
                    var t_string: Dictionary = service.lsp_safe_text(subject_path,
                            "var s = " + dq3 + "\\nclass_name Gd3Phase7Subject\\n" + dq3 + "\\nextends Node\\n")
                    forms_ok = forms_ok and t_string.get("erased", true) == false
                    var t_indent: Dictionary = service.lsp_safe_text(subject_path, "  class_name Gd3Phase7Subject\\nextends Node\\n")
                    forms_ok = forms_ok and t_indent.get("erased", true) == false
                    # Comment tail must not inject a same-line base (review finding): the real base
                    # comes from the next line, and the erase uses the zero-drift blank form.
                    var t_tail: Dictionary = service.lsp_safe_text(subject_path,
                            "class_name Gd3Phase7Subject # extends RefCounted\\nextends Node\\n")
                    var t_tail_lines: PackedStringArray = str(t_tail["text"]).split("\\n")
                    forms_ok = forms_ok and t_tail.get("erased", false) == true \\
                            and t_tail_lines[0] == " ".repeat("class_name Gd3Phase7Subject # extends RefCounted".length()) \\
                            and t_tail_lines[1] == "extends Node" \\
                            and int(t_tail["removed_prefix"]) == 0
                    # The icon string must not inject a base either (`extends` inside the quoted
                    # path is string content, never the header clause).
                    var t_icon: Dictionary = service.lsp_safe_text(subject_path,
                            "class_name Gd3Phase7Subject, \\"res://extends_x.png\\"\\nextends Node\\n")
                    forms_ok = forms_ok and t_icon.get("erased", false) == true \\
                            and int(t_icon["removed_prefix"]) == 0
                    # Adversarial multiline sequence (review finding): a triple-quote run held in a
                    # comment must not arm the string state; the class_name INSIDE the real string
                    # is not a declaration.
                    var t_adv: Dictionary = service.lsp_safe_text(subject_path,
                            "var a = \\"x\\" # " + dq3 + "\\nvar s = " + dq3 + "\\nclass_name Gd3Phase7Subject\\n" + dq3 + "\\nextends Node\\n")
                    forms_ok = forms_ok and t_adv.get("erased", true) == false
                    # Triple-SINGLE-quote multiline strings (the 4.5 tokenizer accepts both quote
                    # chars) must protect content the same way.
                    var sq3 := String.chr(39) + String.chr(39) + String.chr(39)
                    var t_single3: Dictionary = service.lsp_safe_text(subject_path,
                            "var s = " + sq3 + "\\nclass_name Gd3Phase7Subject\\n" + sq3 + "\\nextends Node\\n")
                    forms_ok = forms_ok and t_single3.get("erased", true) == false
                    # An escaped first quote must not close the multiline string early: the
                    # class_name below stays string content.
                    var t_esc: Dictionary = service.lsp_safe_text(subject_path,
                            "var s = " + dq3 + "\\n" + "\\\\" + dq3 + "\\nclass_name Gd3Phase7Subject\\n" + dq3 + "\\nextends Node\\n")
                    forms_ok = forms_ok and t_esc.get("erased", true) == false
                    # An explicit but base-less `extends` (mid-edit / comment-only tail) must fail
                    # admission rather than fall back to the implicit RefCounted default — anchored
                    # on the fixture's RefCounted inner class (where the default WOULD match).
                    var t_empty_base: Dictionary = service.lsp_safe_text(subject_path,
                            "class_name Gd3Phase7Subject__sub__Inner\\nextends # mid-edit\\n")
                    forms_ok = forms_ok and t_empty_base.get("erased", true) == false
                    var t_inner_ok: Dictionary = service.lsp_safe_text(subject_path,
                            "class_name Gd3Phase7Subject__sub__Inner\\nextends RefCounted\\n")
                    forms_ok = forms_ok and t_inner_ok.get("erased", false) == true
                    # `extends ,` (no resolvable token) must fail admission too — never fall back to
                    # the implicit RefCounted default on an explicit-but-empty clause.
                    var t_empty_token: Dictionary = service.lsp_safe_text(subject_path,
                            "class_name Gd3Phase7Subject__sub__Inner\\nextends ,\\n")
                    forms_ok = forms_ok and t_empty_token.get("erased", true) == false
                    # Regular (non-triple) strings may also span physical lines (4.5 tokenizer has
                    # no newline terminator; r_strings.gd) — raw and plain forms alike keep string
                    # content protected.
                    var t_raw_span: Dictionary = service.lsp_safe_text(subject_path,
                            "extends Node\\nvar s = r" + dq + "hello\\nclass_name Gd3Phase7Subject\\n" + dq + "\\n")
                    forms_ok = forms_ok and t_raw_span.get("erased", true) == false
                    var t_plain_span: Dictionary = service.lsp_safe_text(subject_path,
                            "extends Node\\nvar s = " + dq + "hello\\nclass_name Gd3Phase7Subject\\n" + dq + "\\n")
                    forms_ok = forms_ok and t_plain_span.get("erased", true) == false
                    _step("rewrite_forms", forms_ok)
                
                    # --- E2E: with the extension loaded, editing the source must no longer raise
                    # the `hides a native class` pseudo-error, while gdcc diagnostics still flow. ---
                    service.notify_filesystem_changed()
                    var diag_ok: bool = await _wait_diag_ready(service, 45.0)
                    diag_ok = diag_ok and await _wait_diag_current(service, subject_path, 1, 60.0)
                    _step("analysis_ready", diag_ok)
                
                    var clean_result: Dictionary = lang._validate(subject_src, subject_path, true, true, true, true)
                    var clean_ok: bool = clean_result.get("valid", false) == true
                    for e in clean_result.get("errors", []):
                        if str(e.get("message", "")).contains("hides a native class"):
                            clean_ok = false
                    _step("validate_no_hides_error", clean_ok, str(clean_result))
                
                    # Open the subject tab: the decision flip only re-syncs the CURRENT document.
                    EditorInterface.set_main_screen_editor("Script")
                    var subject_res: Resource = ResourceLoader.load(subject_path)
                    if subject_res != null:
                        EditorInterface.edit_resource(subject_res)
                    var code_edit: CodeEdit = null
                    var open_deadline := Time.get_ticks_msec() + 15000
                    while Time.get_ticks_msec() < open_deadline and code_edit == null:
                        await get_tree().process_frame
                        var se := EditorInterface.get_script_editor()
                        var cur := se.get_current_editor()
                        var cur_script := se.get_current_script()
                        if cur != null and cur_script != null and cur_script.resource_path == subject_path:
                            var candidate: Control = cur.get_base_editor()
                            if candidate is CodeEdit:
                                code_edit = candidate as CodeEdit
                    _step("editor_opened", code_edit != null)
                
                    # Decision flip: the signal must clear the memoized admissions, RE-SYNC the
                    # current document (anchored by the LSP document version advancing), and fire a
                    # Phase 5 revalidation (validate counter advances with no driver-side edit).
                    var frames := 0
                    while frames < 5:
                        await get_tree().process_frame
                        frames += 1
                    var memo_before: int = service.erasure_memo_size()
                    var count_before := _lang_validate_count()
                    var lsp_client: GdccLspClient = service.get_lsp_client()
                    var subject_uri: String = lsp_client.path_to_uri(ProjectSettings.globalize_path(subject_path))
                    var doc_version_before: int = lsp_client.document_version_for(subject_uri)
                    GDExtensionManager.emit_signal("extensions_reloaded")
                    var memo_after: int = service.erasure_memo_size()
                    var doc_version_after: int = lsp_client.document_version_for(subject_uri)
                    var flip_fired: bool = await _wait_validate_advance(count_before, 45)
                    _step("decision_flip",
                            memo_after < memo_before and doc_version_after > doc_version_before and flip_fired,
                            "memo %d -> %d, doc v%d -> v%d, fired=%s" % [
                                    memo_before, memo_after, doc_version_before, doc_version_after,
                                    str(flip_fired)])
                
                    # Edit the source: the erased view keeps the LSP silent, and the gdcc-only
                    # diagnostic pair (same family as the gdcc_diag lowering fixture) surfaces.
                    # The edit MUST go through the real editor buffer: an open tab is re-validated
                    # by the editor itself with the BUFFER text, so a driver-side `_validate` with
                    # divergent text would be rolled back by the next editor-driven validation
                    # (version churn starves the merge — the Phase 5 mode drives set_text too).
                    var edited_src := "class_name Gd3Phase7Subject\\nextends Node\\n\\n@onready var camera = $Camera3D\\n"
                    if code_edit != null:
                        code_edit.set_text(edited_src)
                        _last_text_change_msec = Time.get_ticks_msec()
                    # The editor's own idle validation of the open tab pushes the registry version.
                    await _settle_past_idle()
                    var edit_version: int = service.registry().live_version(subject_path)
                    var edit_merged: bool = edit_version == 2 \\
                            and await _wait_diag_current(service, subject_path, edit_version, 60.0)
                    var edit_result: Dictionary = lang._validate(edited_src, subject_path, true, true, true, true)
                    var pair_ok: bool = edit_merged and edit_result.get("valid", true) == false
                    var saw_compile_error := false
                    for e in edit_result.get("errors", []):
                        var msg := str(e.get("message", ""))
                        if msg.begins_with("[gdcc sema.compile_check]") \\
                                and int(e.get("line", -1)) == 4 and int(e.get("column", -1)) == 23:
                            saw_compile_error = true
                        if msg.contains("hides a native class"):
                            pair_ok = false
                    _step("edit_shows_gdcc_not_hides", pair_ok and saw_compile_error,
                            str(edit_result) + " v=" + str(edit_version) + " merged=" + str(edit_merged))
                
                    # Real admission flip in BOTH directions (review finding: memo clearing alone
                    # proves nothing): unloading the fixture extension removes the compiled class,
                    # so erasure must switch OFF; loading it back switches erasure ON again. The
                    # fixture carries `reloadable = true` exactly to allow this (it registers no
                    # ScriptLanguage, so the anti-reload rule does not apply). Both directions also
                    # assert the CURRENT document's re-sync via its LSP document version.
                    var fixture_ext := "res://addons/gdcc_phase7_fixture/gdcc_phase7_fixture.gdextension"
                    var v_before_unload: int = lsp_client.document_version_for(subject_uri)
                    var unload_err: int = GDExtensionManager.unload_extension(fixture_ext)
                    # The post-unload re-sync is deferred to the next frame (the unloading signal
                    # fires before the class set changes).
                    var resynced_off := false
                    var off_frames := 0
                    while off_frames < 30 and not resynced_off:
                        await get_tree().process_frame
                        off_frames += 1
                        resynced_off = lsp_client.document_version_for(subject_uri) > v_before_unload
                    var flip_off: Dictionary = service.lsp_safe_text(subject_path, subject_src)
                    # Content anchor (review finding): the version advance alone could hide a wrong
                    # re-sent text — the post-unload resync must have delivered the RAW buffer text
                    # (erasure off), the post-load resync the erased view of it.
                    var raw_sent_ok: bool = lsp_client.debug_sent_text(subject_uri) == edited_src
                    _step("flip_to_no_erasure",
                            unload_err == OK and resynced_off and raw_sent_ok
                            and flip_off.get("erased", true) == false
                            and not ClassDB.class_exists("Gd3Phase7Subject"),
                            "unload err=" + str(unload_err) + " resynced=" + str(resynced_off)
                            + " raw_sent=" + str(raw_sent_ok))
                    var v_before_load: int = lsp_client.document_version_for(subject_uri)
                    var load_err: int = GDExtensionManager.load_extension(fixture_ext)
                    # The settled hook re-syncs synchronously inside the load call.
                    var v_after_load: int = lsp_client.document_version_for(subject_uri)
                    var expected_erased: String = " ".repeat("class_name Gd3Phase7Subject".length()) \\
                            + edited_src.substr("class_name Gd3Phase7Subject".length())
                    var erased_sent_ok: bool = lsp_client.debug_sent_text(subject_uri) == expected_erased
                    var flip_on: Dictionary = service.lsp_safe_text(subject_path, subject_src)
                    _step("flip_back_to_erasure",
                            load_err == OK and v_after_load > v_before_load and erased_sent_ok
                            and flip_on.get("erased", false) == true
                            and ClassDB.class_exists("Gd3Phase7Subject"),
                            "load err=" + str(load_err) + " resynced=" + str(v_after_load > v_before_load)
                            + " erased_sent=" + str(erased_sent_ok))
                    _step("survived", true)
                """;
    }

    /// Phase 8 (LSP workspace mirror) mode. Kept in a separate text block only because of
    /// the 64KB literal limit — the concatenation must reproduce one continuous GDScript
    /// source, so this block starts with the blank line that separated the sections.
    private static String driverPluginWorkspace() {
        return """
                
                # ---------------- Phase 8: LSP workspace mirror (plan §7 Phase 8) ----------------
                
                # Flattens the (possibly nested) DocumentSymbol tree into a name list — the
                # server-side proof that a `.gd3` entered its parse cache (the server's own
                # disk scan indexes only `.gd`, so a parseable `.gd3` must have come from a
                # client didOpen).
                func _collect_symbol_names(value: Variant, out: Array) -> void:
                    if not (value is Array):
                        return
                    for item in value:
                        if item is Dictionary:
                            out.append(str(item.get("name", "")))
                            _collect_symbol_names(item.get("children", []), out)
                
                # Bounded poll: the exact text last synced for `uri` contains `marker`.
                func _wait_mirror_sent(uri: String, marker: String, timeout_sec: float) -> bool:
                    var client = _service().get_lsp_client()
                    var deadline := Time.get_ticks_msec() + int(timeout_sec * 1000.0)
                    while Time.get_ticks_msec() < deadline:
                        if client.document_version_for(uri) >= 0 \\
                                and client.debug_sent_text(uri).find(marker) >= 0:
                            return true
                        await get_tree().process_frame
                    return false
                
                # Bounded poll for the server-side parse proof via documentSymbol.
                func _wait_doc_symbol(uri: String, symbol_name: String, timeout_sec: float) -> bool:
                    var client = _service().get_lsp_client()
                    var deadline := Time.get_ticks_msec() + int(timeout_sec * 1000.0)
                    while Time.get_ticks_msec() < deadline:
                        var response: Dictionary = client.request_blocking(
                                "textDocument/documentSymbol", {"textDocument": {"uri": uri}}, 2000)
                        if response.get("ok") == true:
                            var names: Array = []
                            _collect_symbol_names(response.get("result"), names)
                            if names.has(symbol_name):
                                return true
                        await get_tree().process_frame
                    return false
                
                # Bounded poll: the server's own publishDiagnostics for `uri` contains a
                # message naming `needle` — the strongest "parsed AND unresolved" proof for a
                # document with intentional resolution errors (its analyzer's own error
                # text). documentSymbol cannot serve here: the 4.5 server answers symbols
                # only for documents whose analysis SUCCEEDED.
                func _wait_diag_naming(uri: String, needle: String, timeout_sec: float) -> bool:
                    var client = _service().get_lsp_client()
                    var deadline := Time.get_ticks_msec() + int(timeout_sec * 1000.0)
                    while Time.get_ticks_msec() < deadline:
                        for d in client.get_diagnostics(uri):
                            if d is Dictionary and str(d.get("message", "")).find(needle) >= 0:
                                return true
                        await get_tree().process_frame
                    return false
                
                func _run_lsp_workspace_mode() -> void:
                    var lang := _find_gd3_language()
                    var port := int(_config["lsp_port"])
                    var closed_port := int(_config["closed_port"])
                    var service := _service()
                    if lang == null or service == null:
                        _step("fixtures_written", false, "language or service missing")
                        return
                    var provider_path := "res://gd3_ws_provider.gd3"
                    var refresh_path := "res://gd3_ws_refresh.gd3"
                    var provider_src := "extends RefCounted\\n\\nfunc mirror_probe_method() -> int:\\n    return 1\\n"
                    var refresh_v1 := "extends RefCounted\\n\\nfunc disk_method_v1() -> int:\\n    return 1\\n"
                    _write_text_file(provider_path, provider_src)
                    _write_text_file(refresh_path, refresh_v1)
                    _step("fixtures_written",
                            FileAccess.get_file_as_string(provider_path) == provider_src \\
                                    and FileAccess.get_file_as_string(refresh_path) == refresh_v1)
                
                    var client = service.get_lsp_client()
                    var provider_uri: String = client.path_to_uri(ProjectSettings.globalize_path(provider_path))
                    var refresh_uri: String = client.path_to_uri(ProjectSettings.globalize_path(refresh_path))
                    _step("lsp_endpoint_closed", _configure_lsp_port(port) and _steer_lsp_client(closed_port))
                    await get_tree().create_timer(0.5).timeout
                    # Negative: with the client steered at a dead port the mirror must hold
                    # back — nothing is didOpen'd while the channel is not READY.
                    _step("mirror_waits_for_ready",
                            client.document_version_for(provider_uri) == -1 \\
                                    and client.document_version_for(refresh_uri) == -1)
                
                    _step("lsp_endpoint", _steer_lsp_client(port))
                    _step("lsp_ready", await _wait_lsp_ready(45.0))
                
                    # The server cannot read `.gd3` from disk: a documentSymbol answer naming
                    # the provider's method proves the didOpen mirror landed server-side; the
                    # client-side sent-text probe pins WHICH text was mirrored.
                    _step("mirror_initial_sync",
                            await _wait_mirror_sent(provider_uri, "mirror_probe_method", 15.0) \\
                                    and await _wait_doc_symbol(provider_uri, "mirror_probe_method", 15.0))
                
                    # The editor-buffer rule (plan §7 Phase 8 item 2): an unsaved buffer
                    # registered through the same `notify_source_changed` `_validate` uses must
                    # replace the mirrored text even though the disk file never changed.
                    var buffer_src := "extends RefCounted\\n\\nfunc mirror_probe_method_v2_buffer() -> int:\\n    return 2\\n"
                    service.notify_source_changed(provider_path, buffer_src)
                    _step("mirror_buffer_wins",
                            await _wait_mirror_sent(provider_uri, "mirror_probe_method_v2_buffer", 15.0))
                
                    # Disk-side change on a path no buffer ever touched: the event-driven scan
                    # refreshes the mirror (the new method becomes visible server-side).
                    var refresh_v2 := "extends RefCounted\\n\\nfunc disk_method_v2() -> int:\\n    return 2\\n"
                    _write_text_file(refresh_path, refresh_v2)
                    service.notify_filesystem_changed()
                    _step("mirror_disk_refresh", await _wait_doc_symbol(refresh_uri, "disk_method_v2", 15.0))
                
                    # Deletion: the mirror didCloses the document (client open-state drops).
                    DirAccess.remove_absolute(ProjectSettings.globalize_path(refresh_path))
                    service.notify_filesystem_changed()
                    var closed_ok := false
                    var close_deadline := Time.get_ticks_msec() + 15000
                    while Time.get_ticks_msec() < close_deadline:
                        if client.document_version_for(refresh_uri) == -1:
                            closed_ok = true
                            break
                        await get_tree().process_frame
                    _step("mirror_delete_close", closed_ok)
                
                    # Tombstone + recreate interlock (review finding): simulate the
                    # reconciler's tombstone mark on a path that EXISTS on disk again (the
                    # module-gated tombstone lift cannot run while the diagnostics module is
                    # absent — exactly the state of this case). The mirror must treat the
                    # file as a normal mirrored path: a close→sync conversion every frame
                    # would advance the LSP document version on every frame.
                    var churn_path := "res://gd3_ws_churn.gd3"
                    _write_text_file(churn_path, "extends RefCounted\\n\\nfunc churn_probe() -> int:\\n    return 1\\n")
                    service.notify_filesystem_changed()
                    var churn_uri: String = client.path_to_uri(ProjectSettings.globalize_path(churn_path))
                    var churn_synced: bool = await _wait_mirror_sent(churn_uri, "churn_probe", 15.0)
                    service.registry().tombstone(churn_path)
                    # Let any immediate conversion settle, then sample the document version
                    # over a 30-frame quiet window: it must not advance at all.
                    await get_tree().process_frame
                    await get_tree().process_frame
                    var churn_v0: int = client.document_version_for(churn_uri)
                    var churn_frames := 0
                    while churn_frames < 30:
                        await get_tree().process_frame
                        churn_frames += 1
                    _step("mirror_tombstone_recreated",
                            churn_synced and churn_v0 > 0 \\
                                    and client.document_version_for(churn_uri) == churn_v0,
                            "v0=%d v1=%d" % [churn_v0, client.document_version_for(churn_uri)])
                
                    # Bulk sync must not starve `_validate` (plan §7 Phase 8 验收): queue 40
                    # fresh files (5 drain frames at 8 ops/frame), then keep validating an
                    # error sample until the mirror's queue is EMPTY. The loop measures calls
                    # across the whole drain (a blocking validate call pauses the frame pump,
                    # so the drain advances exactly one frame between two calls — later calls
                    # are provably mid-bulk, pinned by `budget_overlap`). The per-call bound
                    # is 400ms: the client's hard 150ms inline-wait watermark plus
                    # scheduling/parse slack. Afterwards every one of the 40 URIs must be
                    # mirrored (no sampling — DirAccess order is not a completion witness),
                    # and the error must still bind to line 3: the flood of unrelated
                    # publishDiagnostics must not desync the per-generation FIFO.
                    var bulk_paths: Array = []
                    for i in 40:
                        var bulk_path := "res://gd3_ws_bulk_%02d.gd3" % i
                        _write_text_file(bulk_path,
                                "extends RefCounted\\n\\nfunc bulk_method_%d() -> int:\\n    return %d\\n" % [i, i])
                        bulk_paths.append(bulk_path)
                    service.notify_filesystem_changed()
                    await get_tree().process_frame
                    var err_path := "res://gd3_ws_validate.gd3"
                    var err_src := "extends Node\\n\\nfunc broken( -> void:\\n    pass\\n"
                    var mirror = service.workspace_mirror()
                    var budget_found := false
                    var budget_max_elapsed := 0
                    var budget_overlap := false
                    var budget_errors: Array = []
                    var budget_deadline := Time.get_ticks_msec() + 20000
                    while Time.get_ticks_msec() < budget_deadline \\
                            and (not budget_found or mirror.busy_wanted() or not budget_overlap):
                        var validate_start := Time.get_ticks_msec()
                        var validate_result: Dictionary = lang._validate(
                                err_src, err_path, false, true, true, false)
                        var validate_elapsed := Time.get_ticks_msec() - validate_start
                        if validate_elapsed > budget_max_elapsed:
                            budget_max_elapsed = validate_elapsed
                        if mirror.busy_wanted():
                            budget_overlap = true
                        budget_errors = validate_result.get("errors", [])
                        if not budget_errors.is_empty():
                            budget_found = true
                        await get_tree().process_frame
                    var bulk_all_mirrored := true
                    for p in bulk_paths:
                        var bulk_uri: String = client.path_to_uri(
                                ProjectSettings.globalize_path(str(p)))
                        if client.document_version_for(bulk_uri) < 0:
                            bulk_all_mirrored = false
                            break
                    _step("validate_budget_during_bulk",
                            budget_found and budget_max_elapsed < 400 and budget_overlap \\
                                    and bulk_all_mirrored \\
                                    and int((budget_errors[0] as Dictionary).get("line", 0)) == 3,
                            "max_elapsed=%d overlap=" % budget_max_elapsed + str(budget_overlap))
                
                    # Reconnect replay: server-side documents die with the connection, so after
                    # a forced drop + re-handshake the whole set re-opens — including the
                    # provider with its BUFFER text (the registry truth survives the drop).
                    var epoch_before: int = client.get_ready_epoch()
                    _steer_lsp_client(closed_port)
                    await get_tree().create_timer(0.5).timeout
                    _steer_lsp_client(port)
                    var replay_ready: bool = await _wait_lsp_ready(45.0, epoch_before + 1)
                    var replay_ok: bool = replay_ready \\
                            and await _wait_mirror_sent(provider_uri, "mirror_probe_method_v2_buffer", 20.0) \\
                            and await _wait_doc_symbol(provider_uri, "mirror_probe_method_v2_buffer", 20.0)
                    _step("mirror_reconnect_replay", replay_ok, "ready=" + str(replay_ready))
                
                    for p in bulk_paths + [provider_path, err_path, churn_path]:
                        DirAccess.remove_absolute(ProjectSettings.globalize_path(p))
                    _step("survived", true)
                """;
    }

    /// Phase 9 (`_lookup_code`) mode. Kept in a separate text block only because of the
    /// 64KB literal limit — the concatenation must reproduce one continuous GDScript
    /// source, so this block starts with the blank line that separated the sections.
    private static String driverPluginLookup() {
        return """
                
                # ---------------- Phase 9: symbol lookup (plan §7 Phase 9) ----------------
                
                # The exact safe shape of every `_lookup_code` failure path: result
                # ERR_UNAVAILABLE with the `type` key present (the engine answers
                # ERR_UNAVAILABLE itself when either key is missing).
                func _is_degraded_lookup(result: Dictionary) -> bool:
                    return int(result.get("result", -1)) == ERR_UNAVAILABLE and result.has("type")
                
                # Retries absorb the server's first parse of a freshly synced document and an
                # occasional budget timeout (which disconnects + re-handshakes per the
                # blocking contract); assertions always run on the final answer. Only positive
                # cases use this — a genuinely unknown symbol would spin the full window.
                func _lookup_until_ok(lang: ScriptLanguage, code: String, symbol: String, path: String) -> Dictionary:
                    var deadline := Time.get_ticks_msec() + 30000
                    var result: Dictionary = lang._lookup_code(code, symbol, path, null)
                    while int(result.get("result", -1)) != OK and Time.get_ticks_msec() < deadline:
                        await get_tree().process_frame
                        result = lang._lookup_code(code, symbol, path, null)
                    return result
                
                func _run_lsp_lookup_mode() -> void:
                    var lang := _find_gd3_language()
                    var port := int(_config["lsp_port"])
                    var closed_port := int(_config["closed_port"])
                    var service := _service()
                    if lang == null or service == null:
                        _step("fixtures_written", false, "language or service missing")
                        return
                    var lookup = service.lookup()
                    var sentinel := String.chr(0xFFFF)
                    var provider_path := "res://gd3_lookup_provider.gd3"
                    var consumer_path := "res://gd3_lookup_consumer.gd3"
                    var classy_path := "res://gd3_lookup_classy.gd3"
                    # Path-based extends (NOT class_name): the 4.5 server resolves global
                    # class names through the engine's ScriptServer table — which never lists
                    # `.gd3` classes (that registration is the deferred Stage C, plan §7
                    # Phase 9 `_get_global_class_name`) — while an extends PATH resolves
                    # through the parser's own dependency loading, which the Phase 8 mirror's
                    # didOpen feeds.
                    var provider_src := "extends RefCounted\\n\\nfunc provider_probe() -> int:\\n    return 7\\n"
                    var consumer_src := "extends \\"res://gd3_lookup_provider.gd3\\"\\n\\n" \\
                            + "func probe_local_method() -> int:\\n    return 42\\n\\n" \\
                            + "func use_site() -> void:\\n    var v: int = probe_local_method()\\n" \\
                            + "    var w: int = provider_probe()\\n"
                    var classy_src := "class_name Gd3LookupClassy\\nextends RefCounted\\n\\n" \\
                            + "func classy_probe() -> void:\\n    pass\\n"
                    _write_text_file(provider_path, provider_src)
                    _write_text_file(consumer_path, consumer_src)
                    _write_text_file(classy_path, classy_src)
                    _step("fixtures_written",
                            FileAccess.get_file_as_string(provider_path) == provider_src \\
                                    and FileAccess.get_file_as_string(consumer_path) == consumer_src \\
                                    and FileAccess.get_file_as_string(classy_path) == classy_src)
                
                    var client = service.get_lsp_client()
                    var provider_uri: String = client.path_to_uri(ProjectSettings.globalize_path(provider_path))
                    _step("lsp_endpoint_closed", _configure_lsp_port(port) and _steer_lsp_client(closed_port))
                    await get_tree().create_timer(0.5).timeout
                    # Client not READY: the facade must refuse synchronously with the degraded
                    # shape (no blocking spin against a dead endpoint).
                    var deg: Dictionary = lang._lookup_code(
                            "extends Node\\n\\n" + sentinel + "probe", "probe", consumer_path, null)
                    _step("lookup_degraded", _is_degraded_lookup(deg), str(deg))
                
                    _step("lsp_endpoint", _steer_lsp_client(port))
                    _step("lsp_ready", await _wait_lsp_ready(45.0))
                
                    # White-box pins: the server decides WHICH payloads it emits, so the
                    # mapping contracts are anchored directly (hover text extraction across
                    # MarkupContent / legacy MarkedString forms; first-usable-Location across
                    # bare / array / LocationLink forms; the URI dialect round-trip).
                    var wb_ok: bool = lookup._lookup_hover_text(
                            {"contents": {"kind": "markdown", "value": "doc text"}}) == "doc text"
                    wb_ok = wb_ok and lookup._lookup_hover_text({"contents": "legacy"}) == "legacy"
                    wb_ok = wb_ok and lookup._lookup_hover_text({"contents": [{"value": "a"}, "b"]}) == "a\\nb"
                    wb_ok = wb_ok and lookup._lookup_hover_text(null) == "" and lookup._lookup_hover_text({}) == ""
                    wb_ok = wb_ok and str(lookup._lookup_definition_location(
                            [{"uri": "file:///x.gd3", "range": {"start": {"line": 1}}}]).get("uri", "")) == "file:///x.gd3"
                    wb_ok = wb_ok and str(lookup._lookup_definition_location(
                            {"uri": "file:///y.gd3", "range": {}}).get("uri", "")) == "file:///y.gd3"
                    wb_ok = wb_ok and str(lookup._lookup_definition_location(
                            [{"targetUri": "file:///z.gd3", "targetSelectionRange": {}}]).get("uri", "")) == "file:///z.gd3"
                    wb_ok = wb_ok and lookup._lookup_definition_location(null).is_empty() \\
                            and lookup._lookup_definition_location([]).is_empty() \\
                            and lookup._lookup_definition_location([{"uri": "", "range": {}}]).is_empty()
                    wb_ok = wb_ok and service.lsp_path_from_uri(
                            client.path_to_uri(ProjectSettings.globalize_path(provider_path))) == provider_path
                    wb_ok = wb_ok and service.lsp_path_from_uri("https://example.com/x") == ""
                    # Sentinel-less fallback finder: whole-word boundary, comment-tail skip,
                    # and the suffix-collision negative.
                    wb_ok = wb_ok and lookup._find_word_position(
                            "func probe_local_method() -> int:\\n", "probe_local_method") == 5
                    wb_ok = wb_ok and lookup._find_word_position(
                            "# probe_local_method\\nprobe_local_method", "probe_local_method") == 21
                    wb_ok = wb_ok and lookup._find_word_position("probe_local_methodx", "probe_local_method") == -1
                    _step("lookup_whitebox", wb_ok)
                
                    # Same-file lookup: the sentinel sits at the CALL site of a local method;
                    # the definition points at its declaration line (0-based line 2 → engine
                    # location 3) and stays inside the current tab (no `script` key).
                    var call_code: String = consumer_src.replace("var v: int = probe_local_method()",
                            "var v: int = " + sentinel + "probe_local_method()")
                    var local_result: Dictionary = await _lookup_until_ok(
                            lang, call_code, "probe_local_method", consumer_path)
                    _step("lookup_local_method",
                            int(local_result.get("result", -1)) == OK \\
                                    and int(local_result.get("type", -1)) == 10 \\
                                    and str(local_result.get("description", "")) != "" \\
                                    and str(local_result.get("script_path", "")) == consumer_path \\
                                    and int(local_result.get("location", -1)) == 3 \\
                                    and not local_result.has("script"),
                            str(local_result))
                
                    # Cross-file lookup: the inherited method resolves through the path-based
                    # extends, whose dependency parse the Phase 8 mirror's didOpen feeds (wait
                    # for the provider's server-side parse first). The definition must carry
                    # the provider's Script resource + path + the 1-based def line (0-based
                    # line 2 → 3), or Ctrl+click could never leave the current tab.
                    var mirror_ok: bool = await _wait_doc_symbol(provider_uri, "provider_probe", 20.0)
                    var cross_code: String = consumer_src.replace("var w: int = provider_probe()",
                            "var w: int = " + sentinel + "provider_probe()")
                    var cross_result: Dictionary = await _lookup_until_ok(
                            lang, cross_code, "provider_probe", consumer_path)
                    _step("lookup_cross_file",
                            mirror_ok and int(cross_result.get("result", -1)) == OK \\
                                    and str(cross_result.get("script_path", "")) == provider_path \\
                                    and int(cross_result.get("location", -1)) == 3 \\
                                    and cross_result.get("script") is Script,
                            "mirror=" + str(mirror_ok) + " " + str(cross_result))
                
                    # Documented Stage C gap (negative): a cross-file `class_name` reference
                    # canNOT resolve in 4.5 — the server's global-class resolution reads the
                    # engine ScriptServer table, which never lists `.gd3` classes until the
                    # deferred `_get_global_class_name` stage. Three-part anchor (review
                    # findings): (1) BOTH documents must be server-side parsed first —
                    # otherwise "not in the workspace yet" would masquerade as the Stage C
                    # boundary; (2) the raw channel must answer with BOTH legs succeeded
                    # (`definition_ok`) and EMPTY results — pinning "the server resolved
                    # nothing" instead of any infra failure; (3) the language maps exactly
                    # that answer to the degraded shape.
                    var classy_use_path := "res://gd3_lookup_classy_use.gd3"
                    var classy_uri: String = client.path_to_uri(ProjectSettings.globalize_path(classy_path))
                    var classy_parsed: bool = await _wait_doc_symbol(classy_uri, "classy_probe", 20.0)
                    var gap_clean := "extends Node\\n\\nfunc f() -> void:\\n    var c := Gd3LookupClassy.new()\\n"
                    var gap_response: Dictionary = service.request_lookup(classy_use_path, gap_clean, 3, 14)
                    var gap_use_uri: String = client.path_to_uri(ProjectSettings.globalize_path(classy_use_path))
                    # The consumer's parse + non-resolution proof is the server's OWN analyzer
                    # error naming the class (documentSymbol refuses error-carrying docs).
                    var gap_use_flagged: bool = await _wait_diag_naming(gap_use_uri, "Gd3LookupClassy", 15.0)
                    var gap_server_empty: bool = gap_response.get("ok") == true \\
                            and gap_response.get("definition_ok") == true \\
                            and lookup._lookup_hover_text(gap_response.get("hover")) == "" \\
                            and lookup._lookup_definition_location(gap_response.get("definition")).is_empty()
                    var classy_use_code := "extends Node\\n\\nfunc f() -> void:\\n    var c := " \\
                            + sentinel + "Gd3LookupClassy.new()\\n"
                    var gap_result: Dictionary = lang._lookup_code(
                            classy_use_code, "Gd3LookupClassy", classy_use_path, null)
                    # White-box mapping pin (review finding): the VERIFIED raw answer itself
                    # must map to the degraded shape — otherwise a second end-to-end call
                    # degrading for infra reasons could pass with a broken `_lookup_result`.
                    _step("lookup_class_name_gap",
                            classy_parsed and gap_use_flagged and gap_server_empty \\
                                    and _is_degraded_lookup(lookup._lookup_result(classy_use_path, gap_response)) \\
                                    and _is_degraded_lookup(gap_result),
                            "provider=" + str(classy_parsed) + " flagged=" + str(gap_use_flagged) \\
                                    + " server_empty=" + str(gap_server_empty))
                
                    # Code-point caret anchor (R11 residual): an emoji sits BEFORE the sentinel
                    # on the same line — a UTF-16/byte miscount shifts the request position and
                    # the lookup stops resolving.
                    var cjk_path := "res://gd3_lookup_cjk.gd3"
                    var cjk_code := "extends Node\\n\\nfunc cjk_probe() -> int:\\n    return 1\\n\\n" \\
                            + "func use_it() -> void:\\n    var s := \\"🙂\\" + str(" + sentinel + "cjk_probe())\\n"
                    var cjk_result: Dictionary = await _lookup_until_ok(lang, cjk_code, "cjk_probe", cjk_path)
                    _step("lookup_cjk", int(cjk_result.get("result", -1)) == OK, str(cjk_result))
                
                    # Negative (three-part, review findings): an unknown symbol must degrade —
                    # but first prove (1) the document is parsed AND the symbol genuinely
                    # unresolved — the server's own analyzer error naming the identifier
                    # (documentSymbol refuses error-carrying docs) — and (2) the raw channel
                    # answered with BOTH legs succeeded (`definition_ok`) and EMPTY results;
                    # (3) then the language-level degrade is asserted.
                    var unknown_path := "res://gd3_lookup_unknown.gd3"
                    var unknown_clean := "extends Node\\n\\nfunc f() -> void:\\n    zz_no_such_symbol_zz()\\n"
                    var unknown_response: Dictionary = service.request_lookup(
                            unknown_path, unknown_clean, 3, 4)
                    var unknown_uri: String = client.path_to_uri(ProjectSettings.globalize_path(unknown_path))
                    var unknown_flagged: bool = await _wait_diag_naming(unknown_uri, "zz_no_such_symbol_zz", 15.0)
                    var unknown_server_empty: bool = unknown_response.get("ok") == true \\
                            and unknown_response.get("definition_ok") == true \\
                            and lookup._lookup_hover_text(unknown_response.get("hover")) == "" \\
                            and lookup._lookup_definition_location(unknown_response.get("definition")).is_empty()
                    var unknown_code := "extends Node\\n\\nfunc f() -> void:\\n    " \\
                            + sentinel + "zz_no_such_symbol_zz()\\n"
                    var unknown_result: Dictionary = lang._lookup_code(
                            unknown_code, "zz_no_such_symbol_zz", unknown_path, null)
                    # White-box mapping pin (review finding): the VERIFIED raw answer itself
                    # must map to the degraded shape — a second end-to-end call degrading
                    # for infra reasons could otherwise pass with a broken `_lookup_result`.
                    _step("lookup_unknown_symbol",
                            unknown_flagged and unknown_server_empty \\
                                    and _is_degraded_lookup(lookup._lookup_result(unknown_path, unknown_response)) \\
                                    and _is_degraded_lookup(unknown_result),
                            "flagged=" + str(unknown_flagged) + " server_empty=" + str(unknown_server_empty))
                
                    # Sentinel-less input (not an editor path): the whole-word fallback finds
                    # the declaration occurrence and the lookup still resolves.
                    var fallback_result: Dictionary = await _lookup_until_ok(
                            lang, consumer_src, "probe_local_method", consumer_path)
                    _step("lookup_no_sentinel_fallback", int(fallback_result.get("result", -1)) == OK,
                            str(fallback_result))
                
                    # Disabled plugin: the resident language instance must keep answering the
                    # safe degraded shape (UNINSTALLED entry-point contract, §3.5).
                    EditorInterface.set_plugin_enabled("gdcc", false)
                    await get_tree().process_frame
                    await get_tree().process_frame
                    var resident: ScriptLanguage = _service().get_language_instance()
                    var dis: Dictionary = resident._lookup_code(
                            "extends Node\\n" + sentinel + "probe", "probe", consumer_path, null)
                    EditorInterface.set_plugin_enabled("gdcc", true)
                    await get_tree().process_frame
                    await get_tree().process_frame
                    _step("lookup_disabled_safe", _is_degraded_lookup(dis), str(dis))
                
                    for p in [provider_path, consumer_path, classy_path, classy_use_path, cjk_path, unknown_path]:
                        DirAccess.remove_absolute(ProjectSettings.globalize_path(p))
                    _step("survived", true)
                
                # Temporary safety gate: exercise the actual editor symbol signals, not only
                # a direct call to the language virtual, while the in-process LSP is READY.
                func _run_lookup_suspended_mode() -> void:
                    var lang := _find_gd3_language()
                    var service := _service()
                    if lang == null or service == null:
                        _step("service_ready", false, "language or service missing")
                        return
                    var port := int(_config["lsp_port"])
                    _step("service_ready", _configure_lsp_port(port))
                    var ready: bool = await _wait_lsp_ready(45.0)
                    _step("lsp_ready", ready)
                    if not ready:
                        return

                    var lookup = service.lookup()
                    var hover_only: Dictionary = lookup._lookup_result("res://src/gd3_lookup_suspended.gd3",
                            {"hover": {"contents": {"kind": "markdown", "value": "doc text"}}})
                    _step("lookup_helpers", lookup._lookup_hover_text({"contents": ["a", {"value": "b"}]}) == "a\\nb" \\
                            and str(lookup._lookup_definition_location(
                                    [{"targetUri": "file:///x.gd3", "targetSelectionRange": {}}]).get("uri", "")) \\
                                    == "file:///x.gd3" \\
                            and lookup._find_word_position("# probe\\nprobe", "probe") == 8 \\
                            and int(hover_only.get("result", -1)) == OK \\
                            and str(hover_only.get("description", "")) == "doc text" \\
                            and _is_degraded_lookup(lookup._lookup_result("res://src/gd3_lookup_suspended.gd3", {})))

                    var path := "res://src/gd3_lookup_suspended.gd3"
                    EditorInterface.set_main_screen_editor("Script")
                    var script: Resource = ResourceLoader.load(path)
                    if script != null:
                        EditorInterface.edit_resource(script)
                    var editor := EditorInterface.get_script_editor()
                    var code_edit: CodeEdit = null
                    var deadline := Time.get_ticks_msec() + 15000
                    while Time.get_ticks_msec() < deadline and code_edit == null:
                        await get_tree().process_frame
                        var current := editor.get_current_editor()
                        if current != null and editor.get_current_script() == script:
                            var candidate: Control = current.get_base_editor()
                            if candidate is CodeEdit:
                                code_edit = candidate as CodeEdit
                    _step("editor_opened", code_edit != null and script != null \\
                            and script.resource_path == path)
                    if code_edit == null or script == null:
                        return

                    await get_tree().process_frame
                    await get_tree().process_frame
                    var client = service.get_lsp_client()
                    var uri: String = service.lsp_uri_for(path)
                    var version_before: int = client.document_version_for(uri)
                    var result: Dictionary = lang._lookup_code(code_edit.get_text(), "rotating_speed", path, null)
                    _step("lookup_unavailable", _is_degraded_lookup(result) \\
                            and version_before > 0 and client.document_version_for(uri) == version_before,
                            str(result) + " version=" + str(version_before))

                    var line: int = 5
                    var column: int = code_edit.get_line(line).find("rotating_speed") + 2
                    code_edit.emit_signal("symbol_validate", "rotating_speed")
                    code_edit.emit_signal("symbol_hovered", "rotating_speed", line, column)
                    code_edit.emit_signal("symbol_lookup", "rotating_speed", line, column)
                    await get_tree().process_frame
                    await get_tree().process_frame
                    var cached: Resource = ResourceLoader.get_cached_ref(path)
                    _step("lookup_keeps_path", column > 1 and script.resource_path == path \\
                            and cached == script and editor.get_current_script() == script \\
                            and client.document_version_for(uri) == version_before,
                            "path=" + script.resource_path + " cached=" + str(cached == script) \\
                            + " current=" + str(editor.get_current_script() == script) \\
                            + " column=" + str(column) + " version=" + str(version_before) \\
                            + "/" + str(client.document_version_for(uri)))

                    var edited: String = code_edit.get_text() + "# edited after lookup\\n"
                    code_edit.set_text(edited)
                    script.set_source_code(edited)
                    var path_before_save: String = script.resource_path
                    var save_error: int = ResourceSaver.save(script, path, 0)
                    _step("save_on_original_path", save_error == OK \\
                            and FileAccess.get_file_as_string(path) == edited \\
                            and path_before_save == path and script.resource_path == path,
                            "err=" + str(save_error) + " path_before=" + path_before_save)
                    _step("survived", true)

                # ---------------- Phase 9: auto indent (plan §7 Phase 9) ----------------
                
                # Exact-output anchors for the verbatim mirror of the 4.5
                # `GDScriptLanguage::auto_indent_code` reference: nesting comes from the
                # leading-whitespace stack, never from colons/brackets.
                func _run_auto_indent_mode() -> void:
                    var lang := _find_gd3_language()
                    var service := _service()
                    if lang == null or service == null:
                        _step("indent_tabs_basic", false, "language or service missing")
                        return
                    var tabs_in := "extends Node\\nfunc f() -> void:\\n    pass\\n        pass\\n"
                    _step("indent_tabs_basic",
                            lang._auto_indent_code(tabs_in, 0, 3) \\
                                    == "extends Node\\nfunc f() -> void:\\n\\tpass\\n\\t\\tpass\\n" \\
                                    and service.editor_indent_unit() == "\\t",
                            lang._auto_indent_code(tabs_in, 0, 3))
                
                    # Blank/comment lines pass through byte-identical and never create a stack
                    # level — the comment's own 4 spaces must NOT deepen following lines, and
                    # the dedent to 4 spaces with an empty stack lands at depth 0.
                    var comment_in := "a:\\n    # keep me\\n        b\\n\\n    c\\n"
                    _step("indent_blank_comment_preserved",
                            lang._auto_indent_code(comment_in, 0, 4) == "a:\\n    # keep me\\n\\tb\\n\\nc\\n",
                            lang._auto_indent_code(comment_in, 0, 4))
                
                    # The engine's documented quirk ("not right but gets the job done"): a
                    # dedent landing BETWEEN two stack widths (6 vs [4, 8]) becomes its own
                    # new level — depth 2, not 1.
                    var quirk_in := "a:\\n    b\\n        c\\n      d\\n"
                    _step("indent_dedent_stack_quirk",
                            lang._auto_indent_code(quirk_in, 0, 3) == "a:\\n\\tb\\n\\t\\tc\\n\\t\\td\\n",
                            lang._auto_indent_code(quirk_in, 0, 3))
                
                    # Lines before from_line keep their (mis-indented) text but still feed the
                    # stack; only from_line onward is rewritten.
                    var gate_in := "      a:\\nb\\n        c\\n"
                    _step("indent_from_line_gate",
                            lang._auto_indent_code(gate_in, 2, 2) == "      a:\\nb\\n\\tc\\n",
                            lang._auto_indent_code(gate_in, 2, 2))
                
                    # Spaces mode mirrors `GDScriptLanguage::_get_indentation`: the unit is
                    # `indent/size` spaces when `indent/type` selects Spaces.
                    var settings := EditorInterface.get_editor_settings()
                    settings.set_setting("text_editor/behavior/indent/type", 1)
                    settings.set_setting("text_editor/behavior/indent/size", 2)
                    var spaces_in := "a:\\n    b\\n"
                    var spaces_ok: bool = service.editor_indent_unit() == "  " \\
                            and lang._auto_indent_code(spaces_in, 0, 1) == "a:\\n  b\\n"
                    settings.set_setting("text_editor/behavior/indent/type", 0)
                    spaces_ok = spaces_ok and service.editor_indent_unit() == "\\t"
                    _step("indent_spaces_setting", spaces_ok)
                
                    # Engine quirk (negative): from_line > to_line hits the early break —
                    # the output equals the input byte-for-byte.
                    var inverted_in := "    a\\n    b\\n    c\\n    d\\n"
                    _step("indent_inverted_range",
                            lang._auto_indent_code(inverted_in, 3, 1) == inverted_in,
                            lang._auto_indent_code(inverted_in, 3, 1))
                    _step("survived", true)
                """;
    }

    /// Post-Phase-10 fixes: sync exclusion globs. Separate block only for the 64KB literal
    /// limit — the concatenation must keep one continuous GDScript source.
    private static String driverPluginExclusions() {
        return """
                
                # ---------------- Post-Phase-10: sync exclusion globs ----------------
                
                func _run_sync_exclusions_mode() -> void:
                    var service := _service()
                    if service == null:
                        _step("service_ready", false, "GdccEditorService missing")
                        return
                    var lsp_port := int(_config["lsp_port"])
                    var rpc_port := int(_config["rpc_port"])
                    # Point the editor's GDScript language server at the test port BEFORE it
                    # starts listening (same ordering as the lsp_* cases), then repeat-install
                    # retargets both client endpoints. The LSP side must be LIVE in this mode:
                    # the exclusion anchors include workspace-mirror behavior.
                    var settings := EditorInterface.get_editor_settings()
                    settings.set_setting("network/language_server/remote_host", "127.0.0.1")
                    settings.set_setting("network/language_server/remote_port", lsp_port)
                    var install_ok: bool = service.install(
                            EditorInterface, "127.0.0.1", lsp_port, "127.0.0.1", rpc_port) == OK
                    var lsp_ok: bool = await _wait_lsp_ready(45.0)
                    _step("service_ready", install_ok and lsp_ok,
                            "install=" + str(install_ok) + " lsp=" + str(lsp_ok))
                    if not (install_ok and lsp_ok):
                        return
                
                    # Driver-owned RPC client: observes the module VFS straight from the server.
                    var client := GdccRpcClient.new()
                    add_child(client)
                    client.host = "127.0.0.1"
                    client.port = rpc_port
                    var lsp_client = service.get_lsp_client()
                
                    var ready_ok: bool = await _wait_diag_ready(service, 45.0)
                    var module_id: String = service.get_diag_module_id()
                    var control_path := "res://src/test2.gd3"
                    var addon_path := "res://addons/gdcc/gdcc_diag_cache.gd3"
                    var control_uri: String = service.lsp_uri_for(control_path)
                    var addon_uri: String = service.lsp_uri_for(addon_path)
                
                    # Positive controls FIRST — every absence anchor below is vacuous until the
                    # initial reconciliation AND the mirror's initial replay demonstrably ran:
                    # the project's own (non-excluded) src/test2.gd3 must appear in the module
                    # VFS and on the LSP server. Then the addon's own scripts (default glob
                    # `addons/gdcc/*`) must be absent from BOTH channels.
                    var control := false
                    var control_deadline := Time.get_ticks_msec() + 60000
                    while Time.get_ticks_msec() < control_deadline and not control:
                        var control_read: Dictionary = await client.read_file(
                                module_id, "/src/src/test2.gd3").completed
                        control = control_read["ok"] \\
                                and int(lsp_client.document_version_for(control_uri)) >= 0
                        if not control:
                            await get_tree().create_timer(0.2).timeout
                    var addon_read: Dictionary = await client.read_file(
                            module_id, "/src/addons/gdcc/gdcc_diag_cache.gd3").completed
                    var addon_absent: bool = not addon_read["ok"] \\
                            and int((addon_read.get("error", {}) as Dictionary).get("code", 0)) == -32005 \\
                            and int(lsp_client.document_version_for(addon_uri)) < 0
                    _step("default_excludes_addon", ready_ok and control and addon_absent,
                            "control=" + str(control) + " addon_absent=" + str(addon_absent))
                    if not (ready_ok and control and addon_absent):
                        return
                
                    # The open-tab anchors use a SELF-CONTAINED excluded fixture: admitting a
                    # file that references types absent from the module trips a known frontend
                    # lowering limitation (see UnresolvedTypeLoweringReproTest) that is out of
                    # this feature's scope, so the fixture must not reference foreign types.
                    ProjectSettings.set_setting(service.EXCLUDED_GLOBS_SETTING,
                            PackedStringArray(["addons/gdcc/*", "excluded_ui"]))
                    var ui_path := "res://excluded_ui/open_me.gd3"
                    var ui_src := "class_name ExcludedOpenMe\\nextends Node\\n\\nfunc ping() -> int:\\n    return 1\\n"
                    DirAccess.make_dir_recursive_absolute(
                            ProjectSettings.globalize_path("res://excluded_ui"))
                    _write_text_file(ui_path, ui_src)
                    service.notify_filesystem_changed()
                
                    # Temporary inclusion with a REAL open tab: opening the excluded file lets
                    # the editor's own `_validate` admit it (no driver notify) — the registry
                    # tracks it and the next flight uploads the real content.
                    EditorInterface.set_main_screen_editor("Script")
                    var ui_res: Resource = ResourceLoader.load(ui_path)
                    if ui_res != null:
                        EditorInterface.edit_resource(ui_res)
                    var tab_open := false
                    var tab_deadline := Time.get_ticks_msec() + 15000
                    while Time.get_ticks_msec() < tab_deadline and not tab_open:
                        tab_open = service.is_path_open_in_editor(ui_path)
                        if not tab_open:
                            await get_tree().process_frame
                    # The editor's idle validation beat admits the path into the registry.
                    var admitted := false
                    var admit_deadline := Time.get_ticks_msec() + 30000
                    while Time.get_ticks_msec() < admit_deadline and not admitted:
                        admitted = service.registry().has(ui_path)
                        if not admitted:
                            await get_tree().process_frame
                    # Hurry the flight past the 800ms debounce so the upload lands well
                    # before any later reconcile could evict.
                    service.registry().expire_debounce()
                    var ui_uploaded := false
                    var ui_up_deadline := Time.get_ticks_msec() + 60000
                    while Time.get_ticks_msec() < ui_up_deadline and not ui_uploaded:
                        var ui_up_read: Dictionary = await client.read_file(
                                module_id, "/src/excluded_ui/open_me.gd3").completed
                        ui_uploaded = ui_up_read["ok"] and str(ui_up_read["result"]) == ui_src
                        if not ui_uploaded:
                            await get_tree().create_timer(0.2).timeout
                    _step("excluded_open_tab_admitted", tab_open and admitted and ui_uploaded,
                            "tab=" + str(tab_open) + " admitted=" + str(admitted)
                            + " uploaded=" + str(ui_uploaded))
                    if not (tab_open and admitted and ui_uploaded):
                        return
                
                    # Keep-while-open: a full reconcile runs (proven by the canary's upload)
                    # and the excluded file with an open tab must survive it in BOTH the
                    # registry and the server VFS.
                    _write_text_file("res://excl_canary.gd3",
                            "class_name ExclCanary\\nextends Node\\n")
                    service.notify_filesystem_changed()
                    var canary := false
                    var canary_deadline := Time.get_ticks_msec() + 60000
                    while Time.get_ticks_msec() < canary_deadline and not canary:
                        var canary_read: Dictionary = await client.read_file(
                                module_id, "/src/excl_canary.gd3").completed
                        canary = canary_read["ok"]
                        if not canary:
                            await get_tree().create_timer(0.2).timeout
                    var kept := false
                    if canary:
                        var kept_read: Dictionary = await client.read_file(
                                module_id, "/src/excluded_ui/open_me.gd3").completed
                        kept = kept_read["ok"] and service.registry().has(ui_path)
                    _step("excluded_open_kept_across_reconcile", canary and kept,
                            "canary=" + str(canary) + " kept=" + str(kept))
                    if not (canary and kept):
                        return
                
                    # Simulated admission WITHOUT a tab (driver notify only): the throttled
                    # closed-tab check is the ONLY possible reconcile trigger in this window
                    # (no manual filesystem/settings notify), so the eviction can only come
                    # from it. The channel session is pinned across the window (review
                    # finding): an outage+recovery would ALSO evict the path (recovery
                    # reconcile) while clearing the failure text — a session bump is the only
                    # trace that survives recovery, so without the pin the anchor could pass
                    # with the throttled check removed. NOTE on ordering (review finding): an
                    # excluded path that no tab ever held may legitimately be evicted BEFORE
                    # its first upload — upload-vs-evict ordering is intentionally NOT
                    # asserted (the server-side delete of PREVIOUSLY uploaded content is
                    # pinned deterministically by `settings_change_excludes`/
                    # `buffer_only_excluded` instead). The content is self-contained for the
                    # same known-limitation reason as above.
                    var sim_path := "res://excluded_ui/simulated.gd3"
                    var sim_src := "class_name ExcludedSimulated\\nextends Node\\n"
                    var sim_session: int = service.lifecycle().session()
                    var sim_version: int = service.notify_source_changed(sim_path, sim_src)
                    var sim_admitted: bool = sim_version == 1 and service.registry().has(sim_path)
                    _step("excluded_simulated_admitted", sim_admitted,
                            "version=" + str(sim_version))
                    if not sim_admitted:
                        return
                    var sim_dropped := false
                    var sim_drop_deadline := Time.get_ticks_msec() + 60000
                    while Time.get_ticks_msec() < sim_drop_deadline and not sim_dropped:
                        sim_dropped = not service.registry().has(sim_path)
                        if sim_dropped:
                            var sim_gone: Dictionary = await client.read_file(
                                    module_id, "/src/excluded_ui/simulated.gd3").completed
                            sim_dropped = not sim_gone["ok"] \\
                                    and int((sim_gone.get("error", {}) as Dictionary).get("code", 0)) == -32005
                        if not sim_dropped:
                            await get_tree().create_timer(0.2).timeout
                    var sim_same_session: bool = service.lifecycle().session() == sim_session
                    _step("excluded_simulated_dropped", sim_dropped and sim_same_session,
                            "dropped=" + str(sim_dropped) + " same_session=" + str(sim_same_session))
                    if not (sim_dropped and sim_same_session):
                        return
                
                    # Custom folder exclusion via the wildcard-free directory form: the file
                    # syncs under the default set first...
                    DirAccess.make_dir_recursive_absolute(
                            ProjectSettings.globalize_path("res://excl_probe"))
                    var probe_path := "res://excl_probe/visible.gd3"
                    var probe_src := "class_name ExclProbe\\nextends Node\\n"
                    _write_text_file(probe_path, probe_src)
                    service.notify_filesystem_changed()
                    var synced := false
                    var sync_deadline := Time.get_ticks_msec() + 60000
                    while Time.get_ticks_msec() < sync_deadline and not synced:
                        var probe_read: Dictionary = await client.read_file(
                                module_id, "/src/excl_probe/visible.gd3").completed
                        synced = probe_read["ok"]
                        if not synced:
                            await get_tree().create_timer(0.2).timeout
                    _step("custom_file_synced", synced)
                    if not synced:
                        return
                
                    # ...and reaches the LSP workspace mirror (didOpen). This anchor doubles
                    # as the loop guard for the exclusion below: with the close→sync bounce
                    # bug the drain never returns, the frame pump freezes, and every poll
                    # after the settings edit would starve.
                    var probe_uri: String = service.lsp_uri_for(probe_path)
                    var mirrored := false
                    var mirror_deadline := Time.get_ticks_msec() + 60000
                    while Time.get_ticks_msec() < mirror_deadline and not mirrored:
                        mirrored = int(lsp_client.document_version_for(probe_uri)) >= 0
                        if not mirrored:
                            await get_tree().create_timer(0.2).timeout
                    _step("probe_mirrored_lsp", mirrored)
                    if not mirrored:
                        return
                
                    # A settings edit alone (NO manual filesystem notify — the plugin's
                    # ProjectSettings.settings_changed wiring must trigger the reconcile)
                    # removes the file server-side.
                    ProjectSettings.set_setting(service.EXCLUDED_GLOBS_SETTING,
                            PackedStringArray(["addons/gdcc/*", "excl_probe"]))
                    var excluded := false
                    var exclude_deadline := Time.get_ticks_msec() + 60000
                    while Time.get_ticks_msec() < exclude_deadline and not excluded:
                        var gone_read: Dictionary = await client.read_file(
                                module_id, "/src/excl_probe/visible.gd3").completed
                        excluded = not gone_read["ok"] \\
                                and int((gone_read.get("error", {}) as Dictionary).get("code", 0)) == -32005
                        if not excluded:
                            await get_tree().create_timer(0.2).timeout
                    _step("settings_change_excludes", excluded)
                    if not excluded:
                        return
                
                    # The mirror closes the excluded document (client-side bookkeeping; the
                    # 4.5 server's didClose is intentionally a no-op server-side).
                    var lsp_closed := false
                    var close_deadline := Time.get_ticks_msec() + 60000
                    while Time.get_ticks_msec() < close_deadline and not lsp_closed:
                        lsp_closed = int(lsp_client.document_version_for(probe_uri)) < 0
                        if not lsp_closed:
                            await get_tree().create_timer(0.2).timeout
                    _step("mirror_closes_excluded", lsp_closed)
                    if not lsp_closed:
                        return
                
                    # Buffer-only source (never on disk): accepted and uploaded under the
                    # current set...
                    var buf_path := "res://excl_buf/unsaved.gd3"
                    var buf_src := "class_name ExclBuf\\nextends Node\\n"
                    var buf_version: int = service.notify_source_changed(buf_path, buf_src)
                    var buf_uploaded := false
                    var buf_deadline := Time.get_ticks_msec() + 60000
                    while Time.get_ticks_msec() < buf_deadline and not buf_uploaded:
                        var buf_read: Dictionary = await client.read_file(
                                module_id, "/src/excl_buf/unsaved.gd3").completed
                        buf_uploaded = buf_read["ok"]
                        if not buf_uploaded:
                            await get_tree().create_timer(0.2).timeout
                    _step("buffer_only_uploaded", buf_version == 1 and buf_uploaded,
                            "version=" + str(buf_version) + " uploaded=" + str(buf_uploaded))
                    if not (buf_version == 1 and buf_uploaded):
                        return
                
                    # ...excluded: explicit exclusion must bypass the buffer-preservation rule
                    # and remove it server-side (still no manual filesystem notify)...
                    ProjectSettings.set_setting(service.EXCLUDED_GLOBS_SETTING,
                            PackedStringArray(["addons/gdcc/*", "excl_probe", "excl_buf"]))
                    var buf_gone := false
                    var buf_gone_deadline := Time.get_ticks_msec() + 60000
                    while Time.get_ticks_msec() < buf_gone_deadline and not buf_gone:
                        var buf_gone_read: Dictionary = await client.read_file(
                                module_id, "/src/excl_buf/unsaved.gd3").completed
                        buf_gone = not buf_gone_read["ok"] \\
                                and int((buf_gone_read.get("error", {}) as Dictionary).get("code", 0)) == -32005
                        if not buf_gone:
                            await get_tree().create_timer(0.2).timeout
                    _step("buffer_only_excluded", buf_gone)
                    if not buf_gone:
                        return
                
                    # ...and re-includable WITHOUT hitting disk: the exclusion removal must not
                    # have tombstoned the buffer-only path (a tombstone lifts only via a disk
                    # file, which would permanently refuse this never-saved buffer).
                    ProjectSettings.set_setting(service.EXCLUDED_GLOBS_SETTING,
                            PackedStringArray(["addons/gdcc/*", "excl_probe"]))
                    var buf_re_version: int = service.notify_source_changed(buf_path, buf_src)
                    var buf_back := false
                    var buf_back_deadline := Time.get_ticks_msec() + 60000
                    while Time.get_ticks_msec() < buf_back_deadline and not buf_back:
                        var buf_back_read: Dictionary = await client.read_file(
                                module_id, "/src/excl_buf/unsaved.gd3").completed
                        buf_back = buf_back_read["ok"]
                        if not buf_back:
                            await get_tree().create_timer(0.2).timeout
                    _step("buffer_only_reincluded", buf_re_version == 1 and buf_back,
                            "version=" + str(buf_re_version) + " back=" + str(buf_back))
                    if not (buf_re_version == 1 and buf_back):
                        return
                
                    # Back to the default set: the on-disk probe is re-scanned, its tombstone
                    # lifted, and the file re-uploaded.
                    ProjectSettings.set_setting(service.EXCLUDED_GLOBS_SETTING,
                            service.default_excluded_globs())
                    var restored := false
                    var restore_deadline := Time.get_ticks_msec() + 60000
                    while Time.get_ticks_msec() < restore_deadline and not restored:
                        var back_read: Dictionary = await client.read_file(
                                module_id, "/src/excl_probe/visible.gd3").completed
                        restored = back_read["ok"]
                        if not restored:
                            await get_tree().create_timer(0.2).timeout
                    _step("reinclude_resyncs", restored)
                    if not restored:
                        return
                
                    # Recovery regression (disk-known temporary inclusion): a synced file
                    # that becomes excluded while a tab holds it must survive the exclusion
                    # reconcile AND be re-uploaded + re-analyzed after a module rebuild —
                    # WITHOUT any further edit (the recovery loop guards on `vanished`, not
                    # `is_disk_known`, or this path would be skipped forever).
                    DirAccess.make_dir_recursive_absolute(
                            ProjectSettings.globalize_path("res://excl_recovery"))
                    DirAccess.make_dir_recursive_absolute(
                            ProjectSettings.globalize_path("res://excl_recovery2"))
                    var rec_path := "res://excl_recovery/on_disk.gd3"
                    var rec_src := "class_name ExclRecovery\\nextends Node\\n"
                    _write_text_file(rec_path, rec_src)
                    _write_text_file("res://excl_recovery2/victim.gd3",
                            "class_name ExclVictim\\nextends Node\\n")
                    service.notify_filesystem_changed()
                    var rec_synced := false
                    var rec_deadline := Time.get_ticks_msec() + 60000
                    while Time.get_ticks_msec() < rec_deadline and not rec_synced:
                        var rec_read: Dictionary = await client.read_file(
                                module_id, "/src/excl_recovery/on_disk.gd3").completed
                        var victim_read: Dictionary = await client.read_file(
                                module_id, "/src/excl_recovery2/victim.gd3").completed
                        rec_synced = rec_read["ok"] and victim_read["ok"]
                        if not rec_synced:
                            await get_tree().create_timer(0.2).timeout
                    var rec_res: Resource = ResourceLoader.load(rec_path)
                    if rec_res != null:
                        EditorInterface.edit_resource(rec_res)
                    var rec_tab := false
                    var rec_tab_deadline := Time.get_ticks_msec() + 15000
                    while Time.get_ticks_msec() < rec_tab_deadline and not rec_tab:
                        rec_tab = service.is_path_open_in_editor(rec_path)
                        if not rec_tab:
                            await get_tree().process_frame
                    _step("recovery_setup", rec_synced and rec_tab,
                            "synced=" + str(rec_synced) + " tab=" + str(rec_tab))
                    if not (rec_synced and rec_tab):
                        return
                
                    # Excluding both dirs: the victim (no tab) is evicted — proving the
                    # reconcile ran — while the open-tab file is KEPT.
                    ProjectSettings.set_setting(service.EXCLUDED_GLOBS_SETTING,
                            PackedStringArray(["addons/gdcc/*", "excl_recovery", "excl_recovery2"]))
                    var victim_gone := false
                    var victim_deadline := Time.get_ticks_msec() + 60000
                    while Time.get_ticks_msec() < victim_deadline and not victim_gone:
                        var victim_gone_read: Dictionary = await client.read_file(
                                module_id, "/src/excl_recovery2/victim.gd3").completed
                        victim_gone = not victim_gone_read["ok"] \\
                                and int((victim_gone_read.get("error", {}) as Dictionary).get("code", 0)) == -32005
                        if not victim_gone:
                            await get_tree().create_timer(0.2).timeout
                    var rec_kept := false
                    if victim_gone:
                        var kept_read: Dictionary = await client.read_file(
                                module_id, "/src/excl_recovery/on_disk.gd3").completed
                        rec_kept = kept_read["ok"] and service.registry().has(rec_path)
                    _step("recovery_excluded_kept", victim_gone and rec_kept,
                            "victim_gone=" + str(victim_gone) + " kept=" + str(rec_kept))
                    if not (victim_gone and rec_kept):
                        return
                
                    # Module rebuild (same pattern as gdcc_diag's stale_module_rebuild): the
                    # kept file is disk-known, so ONLY the `vanished`-guarded recovery loop
                    # re-marks it dirty; without that guard it never comes back.
                    service.uninstall()
                    var reinstall2_ok: bool = service.install(
                            EditorInterface, "127.0.0.1", lsp_port, "127.0.0.1", rpc_port) == OK
                    var rebuilt: bool = reinstall2_ok and await _wait_diag_ready(service, 45.0)
                    _step("recovery_module_rebuilt", rebuilt, "reinstall=" + str(reinstall2_ok))
                    if not rebuilt:
                        return
                    var rec_back := false
                    var rec_back_deadline := Time.get_ticks_msec() + 60000
                    while Time.get_ticks_msec() < rec_back_deadline and not rec_back:
                        var rec_back_read: Dictionary = await client.read_file(
                                module_id, "/src/excl_recovery/on_disk.gd3").completed
                        # The cache was cleared by the rebuild, so a CURRENT version-1 read
                        # proves a fresh upload+analysis round landed — no edit happened.
                        rec_back = rec_back_read["ok"] \\
                                and str(rec_back_read["result"]) == rec_src \\
                                and service.is_diag_version_current(rec_path, 1)
                        if not rec_back:
                            await get_tree().create_timer(0.2).timeout
                    _step("recovery_reuploaded", rec_back)
                    _step("survived", true)
                """;
    }

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

    /// A nonexistent launch executable must be reported without crashing the editor. The
    /// report is platform-dependent (Godot 4.5 `OS.create_process`): on Windows
    /// CreateProcessW fails synchronously, the binding returns -1 and the launcher prints
    /// "OS.create_process failed"; on Unix-likes fork() succeeds and the missing program
    /// only fails at the child's execvp() (engine: "Could not create child process"), so the
    /// launcher instead reports that the spawned process "exited before accepting
    /// connections". Either launcher report satisfies the assertion; "no service listening"
    /// and "editor alive" remain enforced by the driver's step sequence.
    @Test
    void launcherReportsMissingExecutableWithoutCrashing() throws Exception {
        var config = new JsonObject();
        config.addProperty("port", findFreePort());
        config.addProperty("launch_command", "gdcc-definitely-missing-binary-xyz serve --port {port}");
        var output = runCase("launch_bad", config);
        var spawnRefused = output.contains("OS.create_process failed");
        var childExitedEarly = output.contains("exited before accepting connections");
        assertTrue(output.contains("GDCC server launcher") && (spawnRefused || childExitedEarly),
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

    /// Phase 3 acceptance: the gdcc diagnostics channel end-to-end — version-gated merge
    /// into `_validate`, displayPath keying, suppression negatives, fix-then-clear, busy
    /// coordination, stale-module (-32001) rebuild, delete/rename reconciliation, and the
    /// ensure_server_hook outage path. Runs against a real in-process gdcc RPC server; the
    /// GDScript LSP rides the CLI-pinned `--lsp-port`.
    @Test
    void gdccDiagnosticsMergeIntoValidate() throws Exception {
        var config = new JsonObject();
        config.addProperty("lsp_port", findFreePort());
        // The hook-outage step needs an RPC endpoint that fails FAST: a closed port leaves
        // Godot's HTTPRequest hanging for the full 30s request timeout, while an HTTP 500
        // answer completes immediately as a transport failure. This blackhole server is not
        // a gdcc RPC server — it just refuses deterministically.
        var blackhole = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        blackhole.createContext("/rpc", exchange -> {
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });
        blackhole.start();
        try (var server = JsonRpcServer.start(new JsonRpcDispatcher(new API()), "127.0.0.1", 0,
                JsonRpcServer.DEFAULT_MAX_REQUEST_BYTES)) {
            config.addProperty("rpc_port", server.port());
            config.addProperty("blackhole_port", blackhole.getAddress().getPort());
            runCase("gdcc_diag", config);
        } finally {
            blackhole.stop(0);
        }
    }

    /// Phase 10 acceptance: the dock Compile button copies the auto-synced diagnostics module
    /// via `module.copy`, compiles the copy with a real native build into its own
    /// `.godot/gdcc/<copy>` directory, keeps the diagnostics module gate responsive
    /// mid-compile, and replaces the stale copy on the second press. Runs against a real
    /// in-process gdcc RPC server (zig-gated through `runCase`).
    @Test
    void dockCompileCopiesDiagnosticsModule() throws Exception {
        var config = new JsonObject();
        config.addProperty("lsp_port", findFreePort());
        // Two real native builds run inside the editor; the default frame budget would cut the
        // editor off mid-compile (the wall-clock process timeout remains the backstop).
        config.addProperty("quit_after", 500000);
        try (var server = JsonRpcServer.start(new JsonRpcDispatcher(new API()), "127.0.0.1", 0,
                JsonRpcServer.DEFAULT_MAX_REQUEST_BYTES)) {
            config.addProperty("rpc_port", server.port());
            runCase("dock_compile", config);
        }
    }

    /// Post-Phase-10 acceptance: the `gdcc/sync/excluded_globs` project setting keeps the
    /// addon's own `.gd3` files out of the diagnostics module by default, refuses to track
    /// excluded open buffers, and reconciles live in both directions when the setting
    /// changes (through the plugin's ProjectSettings.settings_changed wiring). Runs against
    /// a real in-process gdcc RPC server.
    @Test
    void syncExclusionsKeepAddonFilesOutOfModule() throws Exception {
        var config = new JsonObject();
        config.addProperty("lsp_port", findFreePort());
        try (var server = JsonRpcServer.start(new JsonRpcDispatcher(new API()), "127.0.0.1", 0,
                JsonRpcServer.DEFAULT_MAX_REQUEST_BYTES)) {
            config.addProperty("rpc_port", server.port());
            runCase("sync_exclusions", config);
        }
    }

    /// Phase 4 acceptance (plan §4.3): `_complete_code` end-to-end — degraded answers for
    /// the negative paths (client not READY, no sentinel, disabled service), white-box pins
    /// of the kind mapping and insert-text precedence, keyword/member completion through
    /// the real GDScript LSP with the seven mandatory option keys, the code-point caret
    /// anchor (CJK before the caret, text after it), a per-generation FIFO attribution check
    /// on a completion-synced URI, and a post-cycle `_validate` sanity check.
    @Test
    void lspCompletionMapsOptionsAndDegradesSafely() throws Exception {
        var config = new JsonObject();
        config.addProperty("lsp_port", findFreePort());
        config.addProperty("closed_port", findFreePort());
        runCase("lsp_completion", config);
    }

    /// Phase 5 acceptance (plan §7 Phase 5): signal-triggered revalidation surfaces
    /// background analysis results with no user input (ordering-pinned against the editor
    /// idle beat, version-gated, popup-deferred, current-tab-gated), the dock status area
    /// reflects the channel state, and the endpoint is single-sourced through
    /// EditorSettings with live retarget. Runs against a real in-process gdcc RPC server;
    /// `closed_port` is a guaranteed-dead endpoint for the retarget failure path.
    @Test
    void diagRevalidationSurfacesAfterAnalysis() throws Exception {
        var config = new JsonObject();
        config.addProperty("lsp_port", findFreePort());
        config.addProperty("closed_port", findFreePort());
        try (var server = JsonRpcServer.start(new JsonRpcDispatcher(new API()), "127.0.0.1", 0,
                JsonRpcServer.DEFAULT_MAX_REQUEST_BYTES)) {
            config.addProperty("rpc_port", server.port());
            runCase("diag_revalidate", config);
        }
    }

    /// Phase 6+7 acceptance (plan §7): every compiled class answers `_gdcc_get_metadata`
    /// through ClassDB with its provenance (source paths, dotted source name, module, version),
    /// and the LSP sync view erases `class_name` exactly when the ClassDB class proves to be
    /// compiled from the file being edited — killing the unsuppressible `hides a native class`
    /// pseudo-error without touching the disk text or the gdcc analysis input. The case runs
    /// against a real in-process gdcc RPC server and a per-case compiled fixture extension
    /// (whose `source_path` must point into the case project copy, so the once-per-suite addon
    /// build cannot provide it).
    @Test
    void classNameErasureSuppressesHidesNativeClass() throws Exception {
        var config = new JsonObject();
        config.addProperty("lsp_port", findFreePort());
        try (var server = JsonRpcServer.start(new JsonRpcDispatcher(new API()), "127.0.0.1", 0,
                JsonRpcServer.DEFAULT_MAX_REQUEST_BYTES)) {
            config.addProperty("rpc_port", server.port());
            runCase("class_name_erasure", config);
        }
    }

    /// Phase 8 acceptance (plan §7 Phase 8): the LSP workspace mirror didOpens the whole
    /// project `.gd3` set after every LSP (re)connect — with the Phase 7 view transform
    /// and the editor-buffer-wins rule — follows disk and buffer changes, didCloses
    /// deletions, and never starves `_validate`'s bounded inline wait during bulk sync.
    /// `closed_port` is a guaranteed-dead endpoint for the not-READY negative and the
    /// forced reconnect.
    @Test
    void lspWorkspaceMirrorSyncsProjectGd3Set() throws Exception {
        var config = new JsonObject();
        config.addProperty("lsp_port", findFreePort());
        config.addProperty("closed_port", findFreePort());
        runCase("lsp_workspace", config);
    }

    /// Phase 9 acceptance (plan §7 Phase 9 `_lookup_code`): hover/definition proxied to
    /// the GDScript LSP — degraded answers for the negative paths (client not READY,
    /// unknown symbol, disabled service), white-box pins of the payload mapping and URI
    /// round-trip, same-file and cross-file (Phase 8 mirror-backed) lookups with the
    /// LOCAL_VARIABLE type / 1-based location / Script-resource navigation contract, the
    /// code-point caret anchor, and the sentinel-less fallback.
    @Test
    @Disabled("GDScript LSP lookup steals the open .gd3 resource path; restore after fixing the lookup channel")
    void lspLookupProxiesHoverAndDefinition() throws Exception {
        var config = new JsonObject();
        config.addProperty("lsp_port", findFreePort());
        config.addProperty("closed_port", findFreePort());
        runCase("lsp_lookup", config);
    }

    /// Lookup stays unavailable while the LSP is ready, and real editor symbol signals
    /// must not detach an open .gd3 script from its path or prevent saving it.
    @Test
    void disabledLookupKeepsScriptPathAndSaveWorking() throws Exception {
        var config = new JsonObject();
        config.addProperty("lsp_port", findFreePort());
        runCase("lookup_suspended", config);
    }

    /// Phase 9 acceptance (plan §7 Phase 9 `_auto_indent_code`): exact-output anchors for
    /// the verbatim mirror of the 4.5 GDScript reference algorithm — whitespace-stack
    /// nesting, blank/comment passthrough without stack effect, the dedent-between-widths
    /// quirk, the from_line gate, the spaces-indent editor setting, and the pathological
    /// from>to early break. No LSP involved.
    @Test
    void autoIndentMirrorsGdScriptReferenceAlgorithm() throws Exception {
        runCase("auto_indent", new JsonObject());
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
        if (caseName.equals("class_name_erasure")) {
            installPhase7Fixture(projectDir, caseDir);
        }
        if (caseName.equals("lookup_suspended")) {
            Files.writeString(projectDir.resolve("src/gd3_lookup_suspended.gd3"), """
                    extends Node

                    @export var rotating_speed: float = 30.0

                    func _process(delta: float) -> void:
                        rotation_degrees.y += rotating_speed * delta
                    """);
        }

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
        // Cases driving real native builds inside the editor (Phase 10 dock compile) need a
        // much larger quit-after frame budget than interactive-speed cases; the 10-minute
        // process timeout stays the actual backstop.
        var quitAfter = config.has("quit_after") ? config.get("quit_after").getAsInt() : QUIT_AFTER_FRAMES;
        var output = runEditor(godotBinary, projectDir, caseDir, lspPort, quitAfter);
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

    /// Phase 6/7 fixture: compiles `Gd3Phase7Subject` (plus a nested class) into a second
    /// GDExtension whose metadata `source_path` points at the subject file INSIDE this case's
    /// project copy — the provenance match the erasure admission requires can never come from
    /// the once-per-suite addon build (that one compiles from the original `src/`). The
    /// subject source lands on disk before the editor boots so the reconciler mirrors it like
    /// any project file.
    private static void installPhase7Fixture(Path projectDir, Path caseDir) throws IOException {
        var subjectSource = "class_name Gd3Phase7Subject\nextends Node\n\nclass Inner extends RefCounted:\n    pass\n";
        var subjectFile = projectDir.resolve("src/phase7_subject.gd3");
        Files.writeString(subjectFile, subjectSource);
        // Godot paths use '/' on every platform; the admission comparison normalizes both
        // sides, but baking the '/' form here keeps the metadata byte-identical to what
        // `ProjectSettings.globalize_path` reports.
        var subjectAbsolute = subjectFile.toAbsolutePath().normalize().toString().replace('\\', '/');
        var result = EditorAddonProjectInstaller.compileFixtureLibrary(
                caseDir.resolve("fixture-build"), TargetPlatform.getNativePlatform(),
                "gd3_phase7_fixture", "Gd3 Phase7 Fixture",
                List.of(new EditorAddonProjectInstaller.FixtureSource(
                        "/src/phase7_subject.gd3", "res://src/phase7_subject.gd3",
                        subjectAbsolute, subjectSource)));
        assertEquals(CompileResult.Outcome.SUCCESS, result.outcome(),
                () -> "phase7 fixture build failed: " + result.failureMessage()
                        + "\nbuild log:\n" + result.buildLog());
        // Installed MANUALLY (not via installExtension): the fixture keeps `reloadable = true`
        // because the decision-flip steps exercise real `unload_extension`/`load_extension`
        // cycles. The forced `reloadable = false` rewrite exists to protect the addon's
        // ScriptLanguageExtension instance (the ScriptServer raw-pointer hazard); the fixture
        // registers plain data classes only, so the rule does not apply to it.
        var extensionDir = projectDir.resolve("addons/gdcc_phase7_fixture");
        var binDir = extensionDir.resolve("bin");
        Files.createDirectories(binDir);
        Path fixtureLibrary = null;
        for (var artifact : result.artifacts()) {
            var target = binDir.resolve(artifact.getFileName().toString());
            Files.copy(artifact, target, StandardCopyOption.REPLACE_EXISTING);
            if (EditorAddonProjectInstaller.isDynamicLibrary(target.getFileName().toString())) {
                fixtureLibrary = target;
            }
        }
        assertNotNull(fixtureLibrary, "fixture build produced no loadable library");
        Files.writeString(
                extensionDir.resolve("gdcc_phase7_fixture.gdextension"),
                GdextensionMetadataFile.render(
                        "res://addons/gdcc_phase7_fixture/bin/" + fixtureLibrary.getFileName(),
                        COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform()),
                StandardCharsets.UTF_8);
        // The addon install already wrote `.godot/extension_list.cfg` with only its own entry;
        // rewrite it to load BOTH extensions.
        var extensionList = projectDir.resolve(".godot").resolve("extension_list.cfg");
        Files.writeString(extensionList,
                "res://addons/gdcc/gdcc_for_editor.gdextension\n"
                        + "res://addons/gdcc_phase7_fixture/gdcc_phase7_fixture.gdextension\n",
                StandardCharsets.UTF_8);
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
    private static String runEditor(Path godotBinary, Path projectDir, Path caseDir, int lspPort,
                                    int quitAfterFrames)
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
        command.add(String.valueOf(quitAfterFrames));
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

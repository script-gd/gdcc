@tool
extends EditorPlugin

# Editor plugin entry point — interpreted only, never a gdcc compile target.
#
# The compiled classes (`GdccRpcClient`, `GdccEditorService`, ...) come exclusively from the
# installed GDExtension: the `.gd3` sources are never loaded by the engine, so this plugin can
# only be enabled in a project where the compiled extension is installed. This script owns the
# dock, one dock-facing client node, the server launcher, and the low-power busy coordinator;
# the language integration lives in the process-resident `GdccEditorService` (§3.5 of the
# integration plan), which survives plugin disable/enable cycles so open `.gd3` tabs keep a
# valid language instance.

const DockScript := preload("res://addons/gdcc/gdcc_dock.gd")
const LauncherScript := preload("res://addons/gdcc/server_launcher.gd")
# Interpreted like the dock: a compiled `.gd3` class can never extend EditorSyntaxHighlighter
# (extension classes register at the GDExtension SCENE level, editor-only base classes only
# exist after `register_editor_types()`), so the highlighter lives outside the module.
const HighlighterScript := preload("res://addons/gdcc/gdcc_syntax_highlighter.gd")

const SERVICE_NODE_NAME := "GdccEditorService"
const SETTING_LSP_USE_THREAD := "network/language_server/use_thread"
const SETTING_LSP_HOST := "network/language_server/remote_host"
const SETTING_LSP_PORT := "network/language_server/remote_port"
const SETTING_LAUNCH_COMMAND := "gdcc/server/launch_command"
const SETTING_SERVER_HOST := "gdcc/server/host"
const SETTING_SERVER_PORT := "gdcc/server/port"

var _client: GdccRpcClient
var _dock: DockScript
var _launcher: Node
var _service: GdccEditorService
var _use_thread_saved: bool = false
var _use_thread_applied: bool = false
# True when THIS `_enter_tree` primed thread mode before the server listened; an install
# rollback must unprime so the next enable re-probes (the restored setting makes the
# server start main-thread-polled after all).
var _primed_this_enter: bool = false
var _busy_count: int = 0
var _saved_low_processor_mode: bool = true
var _filesystem_connected: bool = false
var _settings_signal_connected: bool = false
# The endpoint pair this plugin instance last relayed into the resident service (via install
# or a settings-changed retarget). The settings-changed relay compares the CONFIGURED pair
# against this — never against the service's live endpoint — so it fires exactly on
# configuration edits and an out-of-band `service.install` (engine tests pin their own
# endpoints) is never yanked back by an unrelated project-settings write.
var _relayed_endpoint: Array = []
# Dedupe for invalid configured pairs: `settings_changed` fires for ANY project-settings
# write, so an unlogged-once invalid pair would spam one push_error per unrelated edit.
# Tracks the last REJECTED pair; a valid read clears it so re-breaking the config re-logs.
var _last_invalid_endpoint: Array = []
# The registered syntax-highlighter template (per-tab instances come from its `_create()`).
var _syntax_highlighter: EditorSyntaxHighlighter = null
# CodeEdit instance IDs whose highlighter this plugin has already settled (manual assignment
# or an observed native selection). The set is what stops the `editor_script_changed` relay
# from re-assigning ours over a user's deliberate dropdown pick on a later focus: a pick
# survives because the tab was recorded the first time we saw it with any highlighter state.
# Instance IDs are session-unique, so closed-tab entries can never match a future editor.
var _handled_highlighter_editors: Dictionary = {}


func _enter_tree() -> void:
    # 1) Resident service node, BEFORE any settings change (a fail-closed abort must leave
    #    zero side effects). A foreign node squatted on the fixed name means we must not
    #    create a second service — two `GD3` language instances would corrupt the editor's
    #    language table.
    var existing := get_tree().root.get_node_or_null(SERVICE_NODE_NAME)
    if existing != null:
        if existing is GdccEditorService:
            _service = existing
        else:
            push_error("GDCC: root node '" + SERVICE_NODE_NAME
                    + "' exists with an unexpected type; script language integration is disabled.")
            return
    else:
        _service = GdccEditorService.new()
        _service.name = SERVICE_NODE_NAME
        get_tree().root.add_child(_service)

    var editor_settings := get_editor_interface().get_editor_settings()
    # 2) Endpoints first (the thread-mode probe below needs them): LSP follows the user's
    #    language-server settings; the gdcc RPC endpoint reads the SAME ProjectSettings pair
    #    the Project Settings dialog edits (`gdcc/server/host|port`) so the background
    #    diagnostics channel and the configured endpoint can never diverge (plan §7 Phase 5
    #    item 3). Defaults are registered a few lines below and therefore always exist by the
    #    time install() runs.
    var lsp_endpoint := _read_lsp_endpoint()
    var lsp_host: String = lsp_endpoint[0]
    var lsp_port: int = lsp_endpoint[1]
    # 3) The GDScript LSP server only polls on the main thread unless threaded mode is on, and
    #    the language's bounded synchronous waits deadlock without it. The engine persists
    #    this setting on editor exit, so the original value must be restored on unload.
    #    A programmatic flip does NOT restart an already-running server (the settings-changed
    #    notification is emitted only by C++ UI flows, plan §2.6), so the gate is whether the
    #    endpoint is listening right now: a listening server stays main-thread-polled for the
    #    rest of this process (mark the session blocking-unsafe, sticky across disable — the
    #    scripted restore does not restart it either); a not-yet-listening server will read
    #    the flipped value at start (primed — remembered so later disable/enable cycles don't
    #    mislatch unsafe once the port is up).
    if editor_settings.has_setting(SETTING_LSP_USE_THREAD):
        _use_thread_saved = bool(editor_settings.get_setting(SETTING_LSP_USE_THREAD))
        if not _use_thread_saved:
            editor_settings.set_setting(SETTING_LSP_USE_THREAD, true)
            _use_thread_applied = true
            if not _service.lsp_blocking_unsafe and not _service.lsp_thread_primed:
                if _lsp_endpoint_listening(lsp_host, lsp_port):
                    _service.lsp_blocking_unsafe = true
                    push_warning("GDCC: plugin enabled while the GDScript language server is already running; it keeps its current polling mode until the editor restarts — .gd3 diagnostics will refresh on editor beats instead of synchronously.")
                else:
                    _service.lsp_thread_primed = true
                    _primed_this_enter = true
    # Launch command (empty = never auto-launch) plus the server endpoint. These are
    # PER-PROJECT settings (project.godot) so a project's server setup is versioned and
    # shared with its contributors; register the schema with defaults, seeding from the
    # pre-2026-10 machine-local EditorSettings values when this project never customized the
    # key (one-time migration; the legacy keys are left untouched for downgrade safety, and
    # the seeded values stay in-memory until the user saves the project).
    var migrated := false
    if not ProjectSettings.has_setting(SETTING_SERVER_HOST):
        var host := "127.0.0.1"
        if editor_settings.has_setting(SETTING_SERVER_HOST):
            var legacy_host := str(editor_settings.get_setting(SETTING_SERVER_HOST)).strip_edges()
            if legacy_host != "" and legacy_host != "127.0.0.1":
                host = legacy_host
                migrated = true
        ProjectSettings.set_setting(SETTING_SERVER_HOST, host)
    ProjectSettings.set_initial_value(SETTING_SERVER_HOST, "127.0.0.1")
    ProjectSettings.add_property_info({"name": SETTING_SERVER_HOST, "type": TYPE_STRING})
    if not ProjectSettings.has_setting(SETTING_SERVER_PORT):
        var port := 6099
        if editor_settings.has_setting(SETTING_SERVER_PORT):
            var legacy_port := int(editor_settings.get_setting(SETTING_SERVER_PORT))
            if legacy_port != 6099 and legacy_port >= 1 and legacy_port <= 65535:
                port = legacy_port
                migrated = true
        ProjectSettings.set_setting(SETTING_SERVER_PORT, port)
    ProjectSettings.set_initial_value(SETTING_SERVER_PORT, 6099)
    ProjectSettings.add_property_info({
        "name": SETTING_SERVER_PORT, "type": TYPE_INT, "hint": PROPERTY_HINT_RANGE,
        "hint_string": "1,65535",
    })
    if not ProjectSettings.has_setting(SETTING_LAUNCH_COMMAND):
        var command := ""
        if editor_settings.has_setting(SETTING_LAUNCH_COMMAND):
            var legacy_command := str(editor_settings.get_setting(SETTING_LAUNCH_COMMAND)).strip_edges()
            if legacy_command != "":
                command = legacy_command
                migrated = true
        ProjectSettings.set_setting(SETTING_LAUNCH_COMMAND, command)
    ProjectSettings.set_initial_value(SETTING_LAUNCH_COMMAND, "")
    ProjectSettings.add_property_info({
        "name": SETTING_LAUNCH_COMMAND,
        "type": TYPE_STRING,
        "hint": PROPERTY_HINT_PLACEHOLDER_TEXT,
        "hint_string": "gdcc serve --host {host} --port {port}",
    })
    if migrated:
        push_warning("GDCC: server settings migrated from machine-local editor settings into this project's settings (gdcc/server/*); save the project to persist them.")
    # Project-level sync exclusion globs: `String.match` patterns against res://-relative
    # paths. Name and default are single-sourced from the resident service (it owns the
    # matching predicate).
    if not ProjectSettings.has_setting(_service.EXCLUDED_GLOBS_SETTING):
        ProjectSettings.set_setting(_service.EXCLUDED_GLOBS_SETTING, _service.default_excluded_globs())
    ProjectSettings.set_initial_value(_service.EXCLUDED_GLOBS_SETTING, _service.default_excluded_globs())
    ProjectSettings.add_property_info({
        "name": _service.EXCLUDED_GLOBS_SETTING, "type": TYPE_PACKED_STRING_ARRAY,
    })

    # 4) Server launcher (single-flight spawn/shutdown coordinator shared by dock and service;
    #    its busy reports point at this plugin's coordinator — see `_report_busy`).
    _launcher = LauncherScript.new()
    _launcher.name = "GdccServerLauncher"
    _launcher.setup(_report_busy)
    add_child(_launcher)

    _client = GdccRpcClient.new()
    add_child(_client)
    _dock = DockScript.new()
    _dock.setup(_client, get_editor_interface(), _report_busy, _launcher, _service)
    add_control_to_bottom_panel(_dock, "GDCC")

    # 5) Install the language integration BEFORE any asynchronous flow (auto-setup may spawn
    #    a server; it must not run when the language side failed to install). The hook
    #    injection precedes install so any service-side connection failure can reach the
    #    launcher immediately. On failure everything created above is rolled back and the
    #    plugin stays inert (fail-closed, no half-installed state).
    _service.ensure_server_hook = Callable(_launcher, "ensure_running")
    # The service's diagnostics scheduler is frame-driven (debounce pump + RPC flights) and
    # therefore reports busy through the single-writer coordinator like the dock and the
    # launcher — otherwise the low-power idle window right after the user stops typing would
    # starve exactly the frames the debounce needs (plan §3.5/§4.4). Connected once per
    # plugin instance; the engine drops the connection when this plugin is freed.
    if not _service.busy_delta.is_connected(_report_busy):
        _service.busy_delta.connect(_report_busy)
    # Phase 5 relay: the service reports revalidation-worthy analysis merges through its
    # own signal; the ACTUAL `validate_script` emission happens HERE in interpreted code.
    # This indirection is load-bearing: a gdcc-compiled foreign-signal `Object.emit_signal`
    # from the service's frame pump empirically delivers to no connection (2026-09-27
    # engine-test bisect), while this interpreted emission reaches them all.
    if not _service.revalidation_requested.is_connected(_on_gdcc_revalidation_requested):
        _service.revalidation_requested.connect(_on_gdcc_revalidation_requested)
    var server_endpoint := _read_server_endpoint()
    # Fail-closed on an unusable configured pair: install on the defaults rather than
    # halfway onto a dead endpoint (the settings-changed path instead keeps the current
    # channel — see `_on_project_settings_changed`).
    var server_host := "127.0.0.1"
    var server_port := 6099
    if not server_endpoint.is_empty():
        server_host = server_endpoint[0]
        server_port = server_endpoint[1]
    _relayed_endpoint = [server_host, server_port]
    var install_err: int = _service.install(get_editor_interface(), lsp_host, lsp_port, server_host, server_port)
    if install_err != OK:
        push_error("GDCC: script language install failed (error %d); editor settings restored." % install_err)
        _rollback_failed_enter_tree()
        return
    # Fire-and-forget: the coroutine awaits RPC responses off this synchronous stack.
    _dock.auto_setup_module()
    # 5.5) Syntax highlighter: registration covers only NEW tabs (ScriptEditor never re-runs
    #    selection for existing ones), so the method also applies to the tab that is current
    #    right now and relays future tab focuses. Best-effort: highlighting must never gate
    #    the language install, so nothing here feeds the failure path.
    _register_syntax_highlighter()
    # 6) Feed file-set changes to the service (connected exactly once across re-enables).
    var filesystem := get_editor_interface().get_resource_filesystem()
    if not filesystem.filesystem_changed.is_connected(_on_filesystem_changed):
        filesystem.filesystem_changed.connect(_on_filesystem_changed)
        _filesystem_connected = true
    # Project-settings edits (exclusion globs, server endpoint) are not filesystem events:
    # forward the (deferred, per-frame coalesced) settings_changed emission so a newly
    # excluded path is reconciled out of the module and a host/port edit retargets the
    # diagnostics channel without waiting for an unrelated file operation.
    if not _settings_signal_connected:
        ProjectSettings.settings_changed.connect(_on_project_settings_changed)
        _settings_signal_connected = true
    # 7) A synchronous scan during the editor's first scan would re-enter it; only a plugin
    #    enabled at runtime (filesystem idle) triggers one deferred scan.
    if not filesystem.is_scanning():
        filesystem.call_deferred("scan")


## Rolls back everything `_enter_tree` created after the resident service node (which is
## deliberately left in place — it is inert while UNINSTALLED and reused on the next enable).
func _rollback_failed_enter_tree() -> void:
    if _dock != null:
        remove_control_from_bottom_panel(_dock)
        _dock.free()
        _dock = null
    if _client != null:
        remove_child(_client)
        _client.free()
        _client = null
    if _launcher != null:
        remove_child(_launcher)
        _launcher.free()
        _launcher = null
    _restore_use_thread()
    if _primed_this_enter:
        _service.lsp_thread_primed = false
        _primed_this_enter = false


## Reads the LSP endpoint from EditorSettings with the engine defaults; shared by
## `_enter_tree` (flip gate) and `_exit_tree` (stale-prime check).
func _read_lsp_endpoint() -> Array:
    var editor_settings := get_editor_interface().get_editor_settings()
    var host := "127.0.0.1"
    var port := 6005
    if editor_settings.has_setting(SETTING_LSP_HOST):
        host = str(editor_settings.get_setting(SETTING_LSP_HOST))
    if editor_settings.has_setting(SETTING_LSP_PORT):
        port = int(editor_settings.get_setting(SETTING_LSP_PORT))
    return [host, port]


## Reads the gdcc server endpoint from the per-project settings (`gdcc/server/host|port`) —
## single-sourced with the Project Settings dialog so the background diagnostics channel can
## never diverge from the configured endpoint (plan §7 Phase 5 item 3). The defaults are
## registered earlier in `_enter_tree`, so this always resolves. Returns [] when the
## configured pair is unusable (a hand-edited project.godot can bypass the dialog's range
## hint), letting each caller fail closed its own way; the rejection is logged once per
## distinct invalid pair (deduped via `_last_invalid_endpoint`).
func _read_server_endpoint() -> Array:
    var host := str(ProjectSettings.get_setting(SETTING_SERVER_HOST, "127.0.0.1")).strip_edges()
    var port := int(ProjectSettings.get_setting(SETTING_SERVER_PORT, 6099))
    if host.is_empty() or port < 1 or port > 65535:
        if [host, port] != _last_invalid_endpoint:
            _last_invalid_endpoint = [host, port]
            push_error("GDCC: invalid gdcc/server project settings (host='%s', port=%d); expected a non-empty host and a port in 1..65535." % [host, port])
        return []
    _last_invalid_endpoint = []
    return [host, port]


## One-shot bounded probe (non-blocking `poll()` + ticks deadline, the only blocking form
## plan §1.2 permits): is something accepting TCP on the LSP endpoint right now? Used to
## decide whether a `use_thread` flip can still reach the server before it starts. The
## probe sends nothing, so it never steals `latest_client_id` diagnostics targeting.
func _lsp_endpoint_listening(host: String, port: int) -> bool:
    var peer := StreamPeerTCP.new()
    if peer.connect_to_host(host, port) != OK:
        return false
    var listening := false
    var deadline := Time.get_ticks_msec() + 150
    while Time.get_ticks_msec() < deadline:
        peer.poll()
        var status := peer.get_status()
        if status == StreamPeerTCP.STATUS_CONNECTED:
            listening = true
            break
        if status != StreamPeerTCP.STATUS_CONNECTING:
            break
    peer.disconnect_from_host()
    return listening


func _exit_tree() -> void:
    # Teardown order is contractual: disconnect feeds → drain the dock's busy reports →
    # uninstall the language integration → restore the LSP thread setting → and only then
    # shut down the spawned server (uninstall may still need the server reachable). Note the
    # scripted restore does NOT restart the engine's LSP server (settings-changed
    # notification is C++-only, plan §2.6); ordering still matters so our own client is
    # disconnected and the language unregistered before the setting flips back.
    var filesystem := get_editor_interface().get_resource_filesystem()
    if _filesystem_connected and filesystem != null:
        if filesystem.filesystem_changed.is_connected(_on_filesystem_changed):
            filesystem.filesystem_changed.disconnect(_on_filesystem_changed)
    _filesystem_connected = false
    if _settings_signal_connected:
        if ProjectSettings.settings_changed.is_connected(_on_project_settings_changed):
            ProjectSettings.settings_changed.disconnect(_on_project_settings_changed)
    _settings_signal_connected = false
    # Highlighter teardown BEFORE the language uninstall: unregistration only removes the
    # template from future tab selection; already-assigned per-tab instances keep working
    # (the interpreted script resource stays alive while a tab references it, and the
    # GDExtension unload does not affect it) and simply stop being re-applied.
    var script_editor := get_editor_interface().get_script_editor()
    if script_editor != null:
        if script_editor.editor_script_changed.is_connected(_on_editor_script_changed):
            script_editor.editor_script_changed.disconnect(_on_editor_script_changed)
        if _syntax_highlighter != null:
            script_editor.unregister_syntax_highlighter(_syntax_highlighter)
    _syntax_highlighter = null
    _handled_highlighter_editors.clear()
    if _dock != null:
        remove_control_from_bottom_panel(_dock)
        # The dock's own `_exit_tree` zeroes its residual busy reports through the coordinator.
        _dock.free()
        _dock = null
    _client = null
    if _service != null:
        _service.uninstall()
    # A primed flag is only truthful while the flipped value still stands. Leaving BEFORE
    # the server listened means the restored original value is what it will read at start —
    # un-prime so the next enable re-probes instead of trusting a stale flag.
    if _use_thread_applied and _service != null and _service.lsp_thread_primed:
        var lsp_endpoint := _read_lsp_endpoint()
        if not _lsp_endpoint_listening(lsp_endpoint[0], lsp_endpoint[1]):
            _service.lsp_thread_primed = false
    _restore_use_thread()
    # Defensive: a reporter that died mid-flight must not leak full-speed mode.
    if _busy_count > 0:
        OS.low_processor_usage_mode = _saved_low_processor_mode
        _busy_count = 0
    if _launcher != null:
        _launcher.shutdown_owned()
        _launcher = null


## Single-writer low-power coordinator: `OS.low_processor_usage_mode` is toggled ONLY here.
## The editor idles without frames under that mode, starving every `process_frame`-driven pump
## (RPC client, launcher). Dock and launcher report ±1 deltas instead of writing the global
## flag themselves, so their save/restore snapshots cannot trample each other. The original
## value is captured on the first busy report and restored when the count drains — never
## hard-coded to `true`, so a user's own setting survives.
func _report_busy(delta: int) -> void:
    var previous := _busy_count
    _busy_count = maxi(0, _busy_count + delta)
    if previous == 0 and _busy_count > 0:
        _saved_low_processor_mode = OS.low_processor_usage_mode
        OS.low_processor_usage_mode = false
    elif _busy_count == 0 and previous > 0:
        OS.low_processor_usage_mode = _saved_low_processor_mode


func _restore_use_thread() -> void:
    if not _use_thread_applied:
        return
    get_editor_interface().get_editor_settings().set_setting(SETTING_LSP_USE_THREAD, _use_thread_saved)
    _use_thread_applied = false


func _on_filesystem_changed() -> void:
    if _service != null:
        _service.notify_filesystem_changed()


## ProjectSettings emits `settings_changed` deferred (per-frame coalesced) for ANY project
## setting write, and 4.5 has no `get_changed_settings()` to filter by key. Two relays share
## the emission: (a) the exclusion-glob filesystem notification (the reconciler coalesces
## bursts and a no-diff pass sends no RPCs), and (b) the server-endpoint retarget. The
## retarget compares the configured pair against the last pair THIS plugin relayed (not the
## service's live endpoint): it fires exactly on host/port edits in the Project Settings
## dialog, an invalid pair is rejected (logged by `_read_server_endpoint`) leaving the
## current channel untouched, and an endpoint the service reached out-of-band (explicit
## install) is never dragged back by an unrelated settings write.
func _on_project_settings_changed() -> void:
    if _service == null:
        return
    _service.notify_filesystem_changed()
    var endpoint := _read_server_endpoint()
    if endpoint.is_empty() or endpoint == _relayed_endpoint:
        return
    _service.retarget_rpc_endpoint(endpoint[0], endpoint[1])
    _relayed_endpoint = endpoint


## Registers the `.gd3` syntax highlighter template (auto-selected for every NEW `.gd3` tab by
## language-name match) and covers already-open tabs: `register_syntax_highlighter` only
## appends to the editor's template list and never re-runs selection for existing tabs (4.5
## script_editor_plugin.cpp), so the current tab is handled immediately and later tab focuses
## come through `editor_script_changed`.
func _register_syntax_highlighter() -> void:
    var script_editor := get_editor_interface().get_script_editor()
    if script_editor == null:
        return
    _syntax_highlighter = HighlighterScript.new()
    script_editor.register_syntax_highlighter(_syntax_highlighter)
    if not script_editor.editor_script_changed.is_connected(_on_editor_script_changed):
        script_editor.editor_script_changed.connect(_on_editor_script_changed)
    _handled_highlighter_editors.clear()
    _apply_highlighter_to_current_editor()


func _on_editor_script_changed(_script: Script) -> void:
    _apply_highlighter_to_current_editor()


## Assigns the `.gd3` highlighter to the CURRENT script-editor tab when it shows one of our
## scripts and still carries a fallback highlighter. Two gates: the resource path (Script has
## no bound `get_language()` in 4.5), and the CURRENT highlighter's native class name —
## `get_class()` reports the most-derived native name even for unregistered classes
## (`_get_class_namev`), so only the two built-in fallbacks (Standard / Plain Text) are
## replaced while a deliberate dropdown pick (e.g. GDScriptSyntaxHighlighter) is never
## touched. This relay is also what corrects the tab-state restore: `script_editor_cache`
## pins the pre-feature "Standard" choice and re-applies it AFTER the native auto-selection
## on every open; the tab's saved state records "GD3" at the next layout save / tab close
## (`get_edit_state()`), so later opens restore ours. Each CodeEdit is settled at
## most once (`_handled_highlighter_editors`), so a user pick is never stomped on a later
## focus.
func _apply_highlighter_to_current_editor() -> void:
    if _syntax_highlighter == null or _service == null or not _service.is_active():
        return
    var script_editor := get_editor_interface().get_script_editor()
    if script_editor == null:
        return
    var script := script_editor.get_current_script()
    # Extension gate, not `script.get_language()`: Script.get_language() is unbound in 4.5,
    # so a GDScript tab would error the whole handler out of the signal relay.
    if script == null or script.resource_path.get_extension() != "gd3":
        return
    var current_editor := script_editor.get_current_editor()
    if current_editor == null:
        return
    var base: Control = current_editor.get_base_editor()
    if not (base is CodeEdit):
        return
    var code_edit := base as CodeEdit
    var edit_id := code_edit.get_instance_id()
    if _handled_highlighter_editors.has(edit_id):
        return
    var current := code_edit.syntax_highlighter
    if current is EditorSyntaxHighlighter and current.get_script() == HighlighterScript:
        _handled_highlighter_editors[edit_id] = true
        return
    if current != null:
        # Replacement gate: only the two built-in fallbacks are replaced. `get_class()`
        # reports the most-derived NATIVE name even for unregistered GDCLASS classes
        # (`_get_class_namev`), so Standard/Plain Text identify by their own names; a
        # deliberate language-highlighter pick keeps its registered name and is never
        # replaced. (`_get_supported_languages()` is a GDVIRTUAL and cannot be called on
        # native instances, which is why this keys on the class name.)
        if not (current is EditorSyntaxHighlighter):
            return
        var is_fallback := current.get_class() == "EditorStandardSyntaxHighlighter" \
                or current.get_class() == "EditorPlainTextSyntaxHighlighter"
        if not is_fallback:
            return
    var inst: EditorSyntaxHighlighter = HighlighterScript.new()
    current_editor.add_syntax_highlighter(inst)
    code_edit.syntax_highlighter = inst
    _handled_highlighter_editors[edit_id] = true


## Relays the service's `revalidation_requested` into the editor's own revalidation
## channel (plan §7 Phase 5 item 1), deferred OUT of the caller's stack first. The
## deferral is load-bearing (2026-09-27 engine-test bisect): emissions initiated anywhere
## inside the service's `SceneTree.process_frame` pump stack — even from this interpreted
## handler nested under it — reach no `validate_script` connection, while the same
## emission from a message-queue-deferred frame reaches them all. The deferred half
## re-runs the service's gate (`should_still_revalidate`: one frame passed; the user may
## have typed or opened the completion popup in between).
func _on_gdcc_revalidation_requested(path: String, version: int) -> void:
    call_deferred("_emit_revalidation_deferred", path, version)


func _emit_revalidation_deferred(path: String, version: int) -> void:
    if _service == null or not _service.should_still_revalidate(path, version):
        return
    var script_editor := get_editor_interface().get_script_editor()
    if script_editor == null:
        return
    var current_script := script_editor.get_current_script()
    if current_script == null or current_script.resource_path != path:
        return
    var current_editor := script_editor.get_current_editor()
    if current_editor == null:
        return
    # 4.5: get_base_editor() returns the bare CodeEdit; the signal lives on its
    # CodeTextEditor parent, so resolve by walking ancestors until the signal appears.
    var wrapper: Node = current_editor.get_base_editor()
    if wrapper != null:
        wrapper = wrapper.get_parent()
    while wrapper != null and not wrapper.has_signal(&"validate_script"):
        wrapper = wrapper.get_parent()
    if wrapper != null:
        wrapper.emit_signal(&"validate_script")

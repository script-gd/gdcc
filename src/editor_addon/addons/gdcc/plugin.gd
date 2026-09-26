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
    #    language-server settings; the gdcc RPC endpoint stays on its fixed default
    #    (deliberately not coupled to the dock input fields).
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
    # Launch-command setting (empty = never auto-launch) plus the dock's persisted server
    # endpoint. Registered so they show in the editor settings; machine-local by design, not
    # project settings.
    if not editor_settings.has_setting(SETTING_LAUNCH_COMMAND):
        editor_settings.set_setting(SETTING_LAUNCH_COMMAND, "")
    editor_settings.add_property_info({
        "name": SETTING_LAUNCH_COMMAND,
        "type": TYPE_STRING,
        "hint": PROPERTY_HINT_PLACEHOLDER_TEXT,
        "hint_string": "gdcc serve --host {host} --port {port}",
    })
    if not editor_settings.has_setting(SETTING_SERVER_HOST):
        editor_settings.set_setting(SETTING_SERVER_HOST, "127.0.0.1")
    editor_settings.add_property_info({"name": SETTING_SERVER_HOST, "type": TYPE_STRING})
    if not editor_settings.has_setting(SETTING_SERVER_PORT):
        editor_settings.set_setting(SETTING_SERVER_PORT, 6099)
    editor_settings.add_property_info({
        "name": SETTING_SERVER_PORT, "type": TYPE_INT, "hint": PROPERTY_HINT_RANGE,
        "hint_string": "0,65535",
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
    _dock.setup(_client, get_editor_interface(), _report_busy, _launcher)
    add_control_to_bottom_panel(_dock, "GDCC")

    # 5) Install the language integration BEFORE any asynchronous flow (auto-setup may spawn
    #    a server; it must not run when the language side failed to install). The hook
    #    injection precedes install so any service-side connection failure can reach the
    #    launcher immediately. On failure everything created above is rolled back and the
    #    plugin stays inert (fail-closed, no half-installed state).
    _service.ensure_server_hook = Callable(_launcher, "ensure_running")
    var install_err: int = _service.install(get_editor_interface(), lsp_host, lsp_port, "127.0.0.1", 6099)
    if install_err != OK:
        push_error("GDCC: script language install failed (error %d); editor settings restored." % install_err)
        _rollback_failed_enter_tree()
        return
    # Fire-and-forget: the coroutine awaits RPC responses off this synchronous stack.
    _dock.auto_setup_module()
    # 6) Feed file-set changes to the service (connected exactly once across re-enables).
    var filesystem := get_editor_interface().get_resource_filesystem()
    if not filesystem.filesystem_changed.is_connected(_on_filesystem_changed):
        filesystem.filesystem_changed.connect(_on_filesystem_changed)
        _filesystem_connected = true
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

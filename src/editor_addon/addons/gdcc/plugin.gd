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
    # 2) The GDScript LSP server only polls on the main thread unless threaded mode is on, and
    #    the language's bounded synchronous waits deadlock without it. The engine persists
    #    this setting on editor exit, so the original value must be restored on unload.
    if editor_settings.has_setting(SETTING_LSP_USE_THREAD):
        _use_thread_saved = bool(editor_settings.get_setting(SETTING_LSP_USE_THREAD))
        if not _use_thread_saved:
            editor_settings.set_setting(SETTING_LSP_USE_THREAD, true)
            _use_thread_applied = true
    # 3) Endpoints: LSP follows the user's language-server settings; the gdcc RPC endpoint
    #    stays on its fixed default (deliberately not coupled to the dock input fields).
    var lsp_host := "127.0.0.1"
    var lsp_port := 6005
    if editor_settings.has_setting(SETTING_LSP_HOST):
        lsp_host = str(editor_settings.get_setting(SETTING_LSP_HOST))
    if editor_settings.has_setting(SETTING_LSP_PORT):
        lsp_port = int(editor_settings.get_setting(SETTING_LSP_PORT))
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


func _exit_tree() -> void:
    # Teardown order is contractual: disconnect feeds → drain the dock's busy reports →
    # uninstall the language integration → restore the LSP thread setting (the restore
    # restarts the engine's LSP server, so it must happen after our teardown) → and only then
    # shut down the spawned server (uninstall may still need the server reachable).
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

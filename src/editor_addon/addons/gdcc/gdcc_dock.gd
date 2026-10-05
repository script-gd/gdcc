@tool
extends VBoxContainer

# Minimal control panel driving the compiled `GdccRpcClient` — interpreted only, never a gdcc
# compile target, so editor-only APIs (EditorInterface, OS) are allowed here.
#
# Every network wait is a signal `await` on the pending object returned by the client; the dock
# never blocks the editor main thread. While any request is in flight the dock temporarily
# disables `OS.low_processor_usage_mode`: the editor enables it by default, and without input
# or redraws the idle main loop would starve the client's frame pump and HTTPRequest. The
# previous mode is restored once the last in-flight action finishes.

# Server error codes (the RPC exception mapping): -32001 turns compile-copy creation into
# delete-and-recopy; -32002 means the stale copy still has a live compile; -32601 detects a
# server too old to know module.copy.
const ERR_MODULE_ALREADY_EXISTS := -32001
const ERR_MODULE_BUSY := -32002
const ERR_METHOD_NOT_FOUND := -32601
# Diagnostics module id convention (gdcc_module_lifecycle.gd3); the compile copy id swaps the
# prefix so it stays scoped to the same project root hash and editor process.
const DIAG_MODULE_PREFIX := "gdcc_editor_diagnostics_"
const COMPILE_MODULE_PREFIX := "gdcc_editor_compile_"
const SETTING_LAUNCH_COMMAND := "gdcc/server/launch_command"
const SETTING_SERVER_HOST := "gdcc/server/host"
const SETTING_SERVER_PORT := "gdcc/server/port"

var _client: GdccRpcClient
var _editor_interface: EditorInterface
# Low-power busy reporting goes to the plugin's coordinator (the single writer of
# `OS.low_processor_usage_mode`); the dock never touches the global flag itself.
var _report_busy: Callable
# Shared server launcher owned by the plugin; used to bring the service up before the first
# connection attempt when the user configured a launch command.
var _launcher: Node
# Resident editor service (plan §7 Phase 5): the status area reads its diagnostics-channel
# snapshot, and endpoint commits are forwarded to its `retarget_rpc_endpoint` so the
# background channel follows the configured server without a language reinstall.
var _service: GdccEditorService

var _host_input: LineEdit
var _port_input: LineEdit
var _module_input: LineEdit
var _launch_command_input: LineEdit
var _log_output: TextEdit
var _status_label: Label
var _action_buttons: Array[Button] = []

var _current_task_id: int = -1
# Endpoint captured at compile start: task ids are scoped to one server instance, so the
# Cancel button must talk to the server that owns the task, not to whatever host/port the
# input fields happen to show now.
var _current_task_host: String = ""
var _current_task_port: int = 0
# Compile copies created by this dock, as {"host", "port", "module_id"} entries. The endpoint
# is recorded per copy because the dock's endpoint can be retargeted between compiles; the
# unload cleanup must talk to the server that actually owns each copy.
var _owned_copies: Array = []
var _busy_count: int = 0


# Injected by plugin.gd before the dock enters the tree; `_ready` builds the UI afterwards.
func setup(client: GdccRpcClient, editor_interface: EditorInterface, busy_reporter: Callable, launcher: Node, service: GdccEditorService) -> void:
    _client = client
    _editor_interface = editor_interface
    _report_busy = busy_reporter
    _launcher = launcher
    _service = service


func _ready() -> void:
    var editor_settings := _editor_interface.get_editor_settings()
    var endpoint_row := HBoxContainer.new()
    endpoint_row.add_child(_make_label("Host"))
    _host_input = _make_line_edit(_read_setting_text(editor_settings, SETTING_SERVER_HOST, "127.0.0.1"), 110)
    endpoint_row.add_child(_host_input)
    endpoint_row.add_child(_make_label("Port"))
    _port_input = _make_line_edit(_read_setting_text(editor_settings, SETTING_SERVER_PORT, "6099"), 60)
    endpoint_row.add_child(_port_input)
    add_child(endpoint_row)
    # Persist endpoint edits like the launch command: machine-local server addressing should
    # survive an editor restart.
    _host_input.text_changed.connect(_on_host_changed)
    _port_input.text_changed.connect(_on_port_changed)
    # Endpoint commits (Enter / focus loss) ALSO retarget the resident service's background
    # diagnostics channel — the editor settings and the effective endpoint must never
    # diverge (plan §7 Phase 5 item 3). Not bound to text_changed: a channel reset per
    # keystroke would churn the module setup, and the service no-ops unchanged endpoints.
    _host_input.text_submitted.connect(_on_endpoint_committed.unbind(1))
    _host_input.focus_exited.connect(_on_endpoint_committed)
    _port_input.text_submitted.connect(_on_endpoint_committed.unbind(1))
    _port_input.focus_exited.connect(_on_endpoint_committed)

    # Diagnostics channel status (plan §7 Phase 5 item 2): a read-only mirror of the
    # service's channel snapshot, refreshed on a slow timer (the channel state is polled,
    # not signaled).
    _status_label = Label.new()
    _status_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
    add_child(_status_label)
    var status_timer := Timer.new()
    status_timer.wait_time = 1.0
    status_timer.autostart = true
    status_timer.timeout.connect(_refresh_status)
    add_child(status_timer)
    _refresh_status()

    var module_row := HBoxContainer.new()
    module_row.add_child(_make_label("Module"))
    _module_input = _make_line_edit("demo", 0)
    _module_input.size_flags_horizontal = Control.SIZE_EXPAND_FILL
    module_row.add_child(_module_input)
    add_child(module_row)

    # Launch-command row: empty means "never auto-launch" (pure passive connect). Reads and
    # writes the machine-local EditorSettings entry directly; the launcher re-reads it before
    # every spawn, so no cached copy is kept here.
    var launch_row := HBoxContainer.new()
    launch_row.add_child(_make_label("Launch"))
    _launch_command_input = _make_line_edit("", 0)
    _launch_command_input.size_flags_horizontal = Control.SIZE_EXPAND_FILL
    _launch_command_input.placeholder_text = "gdcc serve --host {host} --port {port}"
    _launch_command_input.text = _read_setting_text(editor_settings, SETTING_LAUNCH_COMMAND, "")
    _launch_command_input.text_changed.connect(_on_launch_command_changed)
    launch_row.add_child(_launch_command_input)
    add_child(launch_row)

    var session_row := HBoxContainer.new()
    _add_button(session_row, "Ping", _on_ping_pressed)
    _add_button(session_row, "Create", _on_create_module_pressed)
    add_child(session_row)

    var compile_row := HBoxContainer.new()
    _add_button(compile_row, "Analyze", _on_analyze_pressed)
    _add_button(compile_row, "Compile", _on_compile_pressed)
    # Cancel is not tracked: it must stay enabled while busy so an in-flight compile can
    # always be cancelled.
    _add_button(compile_row, "Cancel", _on_cancel_pressed, false)
    add_child(compile_row)

    _log_output = TextEdit.new()
    _log_output.editable = false
    _log_output.custom_minimum_size = Vector2(0.0, 200.0)
    _log_output.size_flags_vertical = Control.SIZE_EXPAND_FILL
    add_child(_log_output)


func _make_label(text: String) -> Label:
    var label := Label.new()
    label.text = text
    return label


func _make_line_edit(text: String, minimum_width: float) -> LineEdit:
    var line_edit := LineEdit.new()
    line_edit.text = text
    if minimum_width > 0.0:
        line_edit.custom_minimum_size.x = minimum_width
    return line_edit


func _add_button(parent: Control, text: String, handler: Callable, track_for_busy: bool = true) -> void:
    var button := Button.new()
    button.text = text
    button.pressed.connect(handler)
    parent.add_child(button)
    if track_for_busy:
        _action_buttons.append(button)


func _set_action_buttons_enabled(enabled: bool) -> void:
    for button in _action_buttons:
        button.disabled = not enabled


func _exit_tree() -> void:
    # GDScript has no try/finally: if the plugin is disabled mid-request, the pending
    # coroutines are dropped with the dock and `_end_busy` never runs — drain the residual
    # count through the coordinator here or the editor would stay in full-speed mode.
    if _busy_count > 0:
        _report_busy.call(-_busy_count)
        _busy_count = 0
    # Best-effort cleanup of this process's compile copies. The frame-pumped RPC client cannot
    # deliver here: by the time the dock exits, the plugin's client child has already left the
    # tree and HTTPRequest refuses to send outside it (ERR_UNCONFIGURED). A bounded blocking
    # HTTPClient POST works without the scene tree; a missed delete self-heals on the next
    # compile via the already-exists replace path, and pid-scoped ids die with any
    # editor-launched server process anyway.
    for entry in _owned_copies:
        _delete_module_blocking(str(entry["host"]), int(entry["port"]), str(entry["module_id"]))
    _owned_copies.clear()


func _log(message: String) -> void:
    _log_output.text += message + "\n"
    # Moving the caret past the last line keeps the newest entry visible.
    _log_output.set_caret_line(_log_output.get_line_count() - 1)


func _log_error(action: String, rpc: Dictionary) -> void:
    var error: Dictionary = rpc["error"]
    _log(action + " failed [" + str(error["code"]) + "]: " + str(error["message"]))


# Busy bookkeeping for the editor-idle liveness mitigation; nesting-safe via a counter. The
# actual global flag write happens in the plugin's coordinator; the dock only reports deltas
# and manages its own buttons. Action buttons are disabled while busy so re-entrant Compile
# presses cannot race `_current_task_id`.
func _begin_busy() -> void:
    if _busy_count == 0:
        _set_action_buttons_enabled(false)
    _busy_count += 1
    _report_busy.call(1)


func _end_busy() -> void:
    _busy_count = maxi(_busy_count - 1, 0)
    _report_busy.call(-1)
    if _busy_count == 0:
        _set_action_buttons_enabled(true)


# Blocking module.delete for teardown, where the frame-pumped client can no longer send.
# Bounded polling keeps a dead server from stalling editor shutdown; the response is
# deliberately not read.
func _delete_module_blocking(host: String, port: int, module_id: String) -> void:
    var http := HTTPClient.new()
    if http.connect_to_host(host, port) != OK:
        return
    var deadline := Time.get_ticks_msec() + 2000
    while http.get_status() == HTTPClient.STATUS_CONNECTING or http.get_status() == HTTPClient.STATUS_RESOLVING:
        http.poll()
        if Time.get_ticks_msec() >= deadline:
            return
        OS.delay_msec(10)
    if http.get_status() != HTTPClient.STATUS_CONNECTED:
        return
    var body := JSON.stringify({
        "jsonrpc": "2.0", "id": 1, "method": "module.delete", "params": {"moduleId": module_id},
    })
    if http.request(HTTPClient.METHOD_POST, "/rpc", ["Content-Type: application/json"], body) != OK:
        return
    while http.get_status() == HTTPClient.STATUS_REQUESTING:
        http.poll()
        if Time.get_ticks_msec() >= deadline:
            return
        OS.delay_msec(10)


func _on_launch_command_changed(new_text: String) -> void:
    _editor_interface.get_editor_settings().set_setting(SETTING_LAUNCH_COMMAND, new_text.strip_edges())


func _on_host_changed(new_text: String) -> void:
    _editor_interface.get_editor_settings().set_setting(SETTING_SERVER_HOST, new_text.strip_edges())


func _on_port_changed(new_text: String) -> void:
    var port := _parsed_port(new_text)
    if port < 0:
        # Never persist an unparseable port: it would poison the next install's endpoint
        # (int("abc") silently becomes 0) — keep the last valid setting instead.
        _log("endpoint: ignored invalid port '" + new_text.strip_edges() + "'")
        return
    _editor_interface.get_editor_settings().set_setting(SETTING_SERVER_PORT, port)


## All-digits port text in 1..65535, or -1 when invalid. The commit path and the settings
## write both gate on this (review finding: bare int() converts garbage to 0 and would
## reset a working diagnostics channel onto a dead endpoint).
func _parsed_port(text: String) -> int:
    var trimmed := text.strip_edges()
    if trimmed.is_empty() or not trimmed.is_valid_int():
        return -1
    var port := int(trimmed)
    if port < 1 or port > 65535:
        return -1
    return port


## Commits the endpoint input fields to the resident service's diagnostics channel (plan
## §7 Phase 5 item 3). Bound to text_submitted/focus_exited (the "commit" gestures); the
## service-side retarget reuses the module lifecycle's channel reset — no language
## reinstall, no LSP disconnect.
func _on_endpoint_committed() -> void:
    if _service == null:
        return
    var port := _parsed_port(_port_input.text)
    if port < 0:
        _log("endpoint commit skipped: invalid port '" + _port_input.text.strip_edges() + "'")
        return
    _service.retarget_rpc_endpoint(_host_input.text.strip_edges(), port)


## Read-only status refresh (timer-driven): configured endpoint vs the service's effective
## endpoint, module readiness, last analysis round and last failure reason.
func _refresh_status() -> void:
    if _status_label == null:
        return
    if _service == null:
        _status_label.text = "Diagnostics: service unavailable"
        return
    var status: Dictionary = _service.diag_channel_status()
    # The configured endpoint is what is actually persisted — the input fields may hold an
    # uncommitted or invalid draft that was (correctly) never written to the settings.
    var settings := _editor_interface.get_editor_settings()
    var configured := _read_setting_text(settings, SETTING_SERVER_HOST, "127.0.0.1") + ":" \
            + _read_setting_text(settings, SETTING_SERVER_PORT, "6099")
    var effective := str(status.get("effective_host", "")) + ":" + str(status.get("effective_port", ""))
    var ready_text := "ready" if status.get("ready", false) else "not ready"
    var last_round := int(status.get("last_round_msec", 0))
    var round_text := "never"
    if last_round > 0:
        round_text = str(maxi(0, (Time.get_ticks_msec() - last_round) / 1000)) + "s ago"
    var failure := str(status.get("last_failure", ""))
    if failure == "":
        failure = "none"
    _status_label.text = "Diagnostics: %s | effective %s (configured %s)\nLast analysis: %s | Last failure: %s" \
            % [ready_text, effective, configured, round_text, failure]


## Reads a machine-local editor setting as text, falling back to the given default when the
## setting was never registered (e.g. a stripped-down editor build).
func _read_setting_text(settings: EditorSettings, key: String, fallback: String) -> String:
    if settings.has_setting(key):
        return str(settings.get_setting(key))
    return fallback


func _apply_endpoint() -> void:
    _client.host = _host_input.text.strip_edges()
    _client.port = int(_port_input.text)


# Returns the trimmed module id, or an empty string after logging why the action was skipped.
func _require_module_id(action: String) -> String:
    var module_id: String = _module_input.text.strip_edges()
    if module_id == "":
        _log(action + " skipped: module id is empty")
    return module_id


func _on_ping_pressed() -> void:
    _apply_endpoint()
    _begin_busy()
    var rpc: Dictionary = await _client.ping().completed
    _end_busy()
    if rpc["ok"]:
        _log("ping: " + str(rpc["result"]))
    else:
        _log_error("ping", rpc)


func _on_create_module_pressed() -> void:
    _apply_endpoint()
    var module_id: String = _require_module_id("create module")
    if module_id == "":
        return
    _begin_busy()
    var rpc: Dictionary = await _client.create_module(module_id, module_id).completed
    _end_busy()
    if rpc["ok"]:
        _log("module created: " + module_id)
    else:
        _log_error("create module", rpc)


func _on_analyze_pressed() -> void:
    _apply_endpoint()
    var module_id: String = _require_module_id("analyze")
    if module_id == "":
        return
    _begin_busy()
    # Pass every argument explicitly: gdcc registers no ClassDB default values
    # (frontend_parameter_default §5.2), so interpreted callers cannot omit them.
    var rpc: Dictionary = await _client.analyze(module_id, false).completed
    _end_busy()
    if not rpc["ok"]:
        _log_error("analyze", rpc)
        return
    var result: Dictionary = rpc["result"]
    _log("analyze outcome: " + str(result["outcome"]))
    # Wire shape: AnalysisResult.diagnostics is a snapshot record wrapping the list.
    var diagnostics: Array = result["diagnostics"]["diagnostics"]
    for diagnostic in diagnostics:
        var line: String = "  " + str(diagnostic["severity"]) + " [" + str(diagnostic["category"]) + "] " \
                + str(diagnostic["message"]) + " (" + str(diagnostic["sourcePath"]) + ")"
        if diagnostic.get("range", null) != null:
            line += " @ " + str(diagnostic["range"])
        _log(line)
    if diagnostics.is_empty():
        _log("  no diagnostics")


# Creates (or refreshes) the compile copy of the diagnostics module and points the copy's
# build directory at its own `.godot/gdcc/<copy>` host dir. Returns the copy's module id, or
# "" after logging why the compile cannot proceed. `call_rpc` is used instead of typed
# wrappers because the installed compiled extension may predate them (the same reason the old
# auto setup avoided `get_compile_options`).
func _prepare_compile_copy(source_module_id: String) -> String:
    var copy_module_id: String = source_module_id.replace(DIAG_MODULE_PREFIX, COMPILE_MODULE_PREFIX)
    if copy_module_id == source_module_id:
        # Defensive fallback if the diagnostics id convention ever changes: the copy must
        # never alias its source.
        copy_module_id = source_module_id + "_compile"
    var copied: Dictionary = await _client.call_rpc(
            "module.copy", {"sourceModuleId": source_module_id, "newModuleId": copy_module_id}).completed
    if not copied["ok"]:
        var code: int = int(copied["error"]["code"])
        if code == ERR_METHOD_NOT_FOUND:
            _log("compile failed: this gdcc server predates module.copy; upgrade the server")
            return ""
        if code == ERR_MODULE_ALREADY_EXISTS:
            # Stale copy from the previous compile (or a crashed session): replace it so every
            # compile runs the latest synced sources.
            var deleted: Dictionary = await _client.delete_module(copy_module_id).completed
            if not deleted["ok"] and int(deleted["error"]["code"]) == ERR_MODULE_BUSY \
                    and _current_task_id > 0 \
                    and _current_task_host == _client.host and _current_task_port == _client.port:
                # The previous compile (started by this dock on THIS endpoint, e.g. its poll
                # timed out) still holds the copy's gate: cancel it, wait for a terminal
                # state, retry once. A task recorded for a different endpoint is not ours to
                # cancel here — fall through to the plain failure log.
                await _client.cancel_compile_task(_current_task_id).completed
                var cancel_deadline := Time.get_ticks_msec() + 15000
                var terminal := false
                while Time.get_ticks_msec() < cancel_deadline and not terminal:
                    var task_view: Dictionary = await _client.get_compile_task(_current_task_id).completed
                    terminal = task_view["ok"] and str(task_view["result"]["state"]) in ["SUCCEEDED", "FAILED", "CANCELED"]
                    if not terminal:
                        await get_tree().create_timer(0.25).timeout
                if terminal:
                    _current_task_id = -1
                    deleted = await _client.delete_module(copy_module_id).completed
            if not deleted["ok"]:
                _log_error("delete stale compile copy", deleted)
                return ""
            copied = await _client.call_rpc(
                    "module.copy", {"sourceModuleId": source_module_id, "newModuleId": copy_module_id}).completed
        if not copied["ok"]:
            _log_error("copy diagnostics module", copied)
            return ""
    # `_client.host/port` are stable for the whole flow: `_apply_endpoint` only runs from
    # busy-gated button handlers (disabled while this flow is in flight) and Cancel now uses
    # its own pinned client instead of mutating the shared one.
    var already_tracked := false
    for entry in _owned_copies:
        if entry["module_id"] == copy_module_id and entry["host"] == _client.host and entry["port"] == _client.port:
            already_tracked = true
    if not already_tracked:
        _owned_copies.append({"host": _client.host, "port": _client.port, "module_id": copy_module_id})
    # options.set replaces the whole snapshot, so fetch the full options.get shape and edit
    # only projectPath. The copy inherits the diagnostics module's options verbatim; its
    # projectPath must be exclusive (server concurrency contract: no shared build dirs).
    var fetched: Dictionary = await _client.call_rpc("options.get", {"moduleId": copy_module_id}).completed
    if not fetched["ok"]:
        _log_error("get options", fetched)
        return ""
    var compile_options: Dictionary = fetched["result"]
    # Build under the project's own .godot dir: host-side generated C and native artifacts
    # stay out of res:// so Godot never tries to import them.
    var project_path: String = ProjectSettings.globalize_path("res://.godot/gdcc/" + copy_module_id)
    compile_options["projectPath"] = project_path
    var applied: Dictionary = await _client.call_rpc(
            "options.set", {"moduleId": copy_module_id, "compileOptions": compile_options}).completed
    if not applied["ok"]:
        _log_error("set options", applied)
        return ""
    _log("compile copy ready: " + copy_module_id + " (projectPath: " + project_path + ")")
    return copy_module_id


# Compile flow (plan §7 Phase 10): snapshot the service's private diagnostics module — kept
# continuously in sync by the reconciler — into a per-process compile copy, then compile the
# copy. The diagnostics module never hosts a compile, so its module gate stays free for
# editor analysis traffic while the native build runs.
func _on_compile_pressed() -> void:
    _apply_endpoint()
    if _service == null or not _service.is_diag_ready():
        _log("compile skipped: diagnostics channel is not ready yet")
        return
    # The diagnostics module lives on the service's effective endpoint; refuse to copy by id
    # on a different server (uncommitted field edits), where the -32001 replace path could
    # delete a stranger's module.
    var channel: Dictionary = _service.diag_channel_status()
    var service_host := str(channel.get("effective_host", ""))
    var service_port := int(channel.get("effective_port", 0))
    if service_host != _client.host or service_port != _client.port:
        _log("compile skipped: endpoint fields (" + _client.host + ":" + str(_client.port)
                + ") differ from the diagnostics channel (" + service_host + ":" + str(service_port)
                + "); commit the endpoint first (Enter in the Host/Port fields)")
        return
    var source_module_id: String = _service.get_diag_module_id()
    if source_module_id == "":
        _log("compile skipped: diagnostics module id is not available yet")
        return
    _begin_busy()
    var module_id: String = await _prepare_compile_copy(source_module_id)
    if module_id == "":
        _end_busy()
        return
    var started: Dictionary = await _client.start_compile(module_id).completed
    if not started["ok"]:
        _end_busy()
        _log_error("compile start", started)
        return
    _current_task_id = int(started["result"]["taskId"])
    _current_task_host = _client.host
    _current_task_port = _client.port
    # Snapshot the identity locally: Cancel may clear the shared field while this coroutine is
    # suspended, so the whole poll loop, logs, and terminal handling use the local copy.
    var task_id: int = _current_task_id
    _log("compile started: task " + str(task_id))
    # Wall-clock deadline polling on the task snapshot — the only progress channel that does
    # not block behind the module gate while the compile runs (120s covers cold native builds).
    var deadline_msec: int = Time.get_ticks_msec() + 120000
    var last_progress_line: String = ""
    while Time.get_ticks_msec() < deadline_msec:
        var polled: Dictionary = await _client.get_compile_task(task_id).completed
        if not polled["ok"]:
            _end_busy()
            _log_error("compile poll", polled)
            return
        var task: Dictionary = polled["result"]
        var state: String = str(task["state"])
        var progress_line: String = state + " " + str(task["stage"]) + " " \
                + str(task["completedUnits"]) + "/" + str(task["totalUnits"])
        if progress_line != last_progress_line:
            last_progress_line = progress_line
            _log("task " + str(task_id) + ": " + progress_line)
        if state == "SUCCEEDED" or state == "FAILED" or state == "CANCELED":
            _log("task finished: createdAt=" + str(task["createdAt"])
                    + " completedAt=" + str(task["completedAt"]))
            if task["result"] != null:
                _log("compile outcome: " + str(task["result"]["outcome"]))
            if state == "SUCCEEDED":
                var last_result: Dictionary = await _client.get_last_compile_result(module_id).completed
                if last_result["ok"] and last_result["result"] != null:
                    _log("last result outcome: " + str(last_result["result"]["outcome"]))
            if _current_task_id == task_id:
                _current_task_id = -1
            _end_busy()
            return
        await get_tree().create_timer(0.25).timeout
    _end_busy()
    # The task keeps running server-side and `_current_task_id` is deliberately kept: pressing
    # Compile again cancels it through the stale-copy busy path.
    _log("compile poll timed out (task " + str(task_id) + " still running; press Cancel, or Compile again to cancel and replace it)")


func _on_cancel_pressed() -> void:
    if _current_task_id < 0:
        _log("cancel skipped: no compile task has been started from this dock")
        return
    # Snapshot the identity locally: the compile poll may clear the shared field while this
    # coroutine is suspended, so the request and the log line must use the local copy.
    var task_id: int = _current_task_id
    # Cancel must reach the server that owns the task WITHOUT mutating the shared client: the
    # client reads host/port when the queue sends (not when calls enqueue), so rewriting it
    # here could reroute an in-flight compile flow's later RPCs.
    var cancel_client := GdccRpcClient.new()
    add_child(cancel_client)
    cancel_client.host = _current_task_host
    cancel_client.port = _current_task_port
    _begin_busy()
    var rpc: Dictionary = await cancel_client.cancel_compile_task(task_id).completed
    cancel_client.queue_free()
    _end_busy()
    if rpc["ok"]:
        var state: String = str(rpc["result"]["state"])
        _log("cancel requested: task " + str(task_id) + " state " + state)
        if (state == "SUCCEEDED" or state == "FAILED" or state == "CANCELED") \
                and _current_task_id == task_id:
            _current_task_id = -1
    else:
        _log_error("cancel", rpc)


# Plugin-load entry point (called once by plugin.gd after the dock enters the tree): ensures
# the compile service is reachable (launching it first when a launch command is configured),
# then points the module field at the service's private diagnostics module once announced —
# Compile copies that module on demand (Phase 10), so no dock-owned module is created here.
# Failures are only logged: the server may simply not be running yet, and the manual buttons
# stay usable.
func auto_setup_module() -> void:
    _apply_endpoint()
    if _launcher != null:
        _begin_busy()
        var ensure_result: Array = await _launcher.ensure_running_async(_client.host, _client.port)
        _end_busy()
        if int(ensure_result[0]) != OK:
            _log("compile service not reachable and could not be launched (error "
                    + str(ensure_result[0]) + "); continuing with passive connection")
    # The diagnostics module is created asynchronously by the service lifecycle; wait briefly
    # so the inspection field lands on a real module instead of the placeholder default.
    var deadline_msec: int = Time.get_ticks_msec() + 10000
    while Time.get_ticks_msec() < deadline_msec:
        if _service != null:
            var diag_module_id: String = _service.get_diag_module_id()
            if diag_module_id != "":
                _module_input.text = diag_module_id
                _log("inspecting diagnostics module: " + diag_module_id)
                return
        await get_tree().create_timer(0.5).timeout
    _log("diagnostics module not announced yet; the module field keeps its current value")

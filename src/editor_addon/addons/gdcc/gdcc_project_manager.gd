@tool
extends Node

# Shared project operations, independent of either editor view. This remains interpreted:
# compiled addon classes cannot use coroutines, and deployment needs editor-only APIs.
signal log_message(message: String)
signal state_changed
signal diagnostics_module_changed(module_id: String)

# Server error codes (the RPC exception mapping): -32001 turns compile-copy creation into
# delete-and-recopy; -32002 means the stale copy still has a live compile; -32601 detects a
# server too old to know module.copy.
const ERR_MODULE_ALREADY_EXISTS := -32001
const ERR_MODULE_BUSY := -32002
const ERR_METHOD_NOT_FOUND := -32601
# Build identities are project-scoped; diagnostics remain private to each editor process.
const COMPILE_MODULE_PREFIX := "gdcc_editor_compile_"
const ERR_MODULE_NOT_FOUND := -32000
const BUILD_EXTENSION_PATH := "res://bin/gdcc.gdextension"
const SETTING_LAUNCH_COMMAND := "gdcc/compilation_server/launch_command"
const SETTING_SERVER_HOST := "gdcc/server/host"
const SETTING_SERVER_PORT := "gdcc/server/port"
const SETTING_OPTIMIZATION_LEVEL := "gdcc/build/optimization_level"
const SETTING_STRICT_MODE := "gdcc/build/strict_mode"
const SETTING_C_COMPILER_KIND := "gdcc/build/c_compiler_kind"

var _client: GdccRpcClient
# Low-power busy reporting goes to the plugin's coordinator (the single writer of
# `OS.low_processor_usage_mode`); this node never touches the global flag itself.
var _report_busy: Callable
# Shared server launcher owned by the plugin; used to bring the service up before the first
# connection attempt when the user configured a launch command.
var _launcher: Node
# Resident editor service: the status area reads its diagnostics-channel
# snapshot, and the project RPC endpoint is sourced from it (the service follows the
# per-project `gdcc/server/*` settings — the single configuration source).
var _service: GdccEditorService

var diagnostics_module_id: String = ""
var last_build_time: int = 0
var server_version: String = "Not connected"
var _version_endpoint: Array = []
var _version_busy: bool = false
var available_c_compiler_kinds: Array[String] = []
var _compiler_warning: String = ""
var _server_refresh_msec: int = -5000

var _current_task_id: int = -1
# Endpoint captured at compile start: task ids are scoped to one server instance, so the
# Cancellation must talk to the server that owns the task, not to whatever endpoint the
# service has since been retargeted to.
var _current_task_host: String = ""
var _current_task_port: int = 0
# Compile copies created by this manager, as {"host", "port", "module_id"} entries. The endpoint
# is recorded per copy because the endpoint can be retargeted between compiles; the
# unload cleanup must talk to the server that actually owns each copy.
var _owned_copies: Array = []
var _compile_owner_token: String = Crypto.new().generate_random_bytes(16).hex_encode()
var _busy_count: int = 0
var _build_in_progress: bool = false
var _pending_activation: Dictionary = {}
var _filesystem_generation: int = 0
var _build_options: Dictionary = {"optimizationLevel": "DEBUG", "strictMode": false, "cCompilerKind": null}


# Dependencies are injected before this shared node enters the editor tree.
func setup(client: GdccRpcClient, busy_reporter: Callable, launcher: Node, service: GdccEditorService) -> void:
    _client = client
    _report_busy = busy_reporter
    _launcher = launcher
    _service = service


func _ready() -> void:
    var saved_time: Variant = EditorInterface.get_editor_settings().get_project_metadata("gdcc", "last_build_time", 0)
    if saved_time is String:
        # Previously persisted local datetime strings have no offset; use the current local bias.
        if saved_time != "":
            var local_seconds := Time.get_unix_time_from_datetime_string(saved_time)
            if local_seconds > 0:
                last_build_time = local_seconds - int(Time.get_time_zone_from_system()["bias"]) * 60
                EditorInterface.get_editor_settings().set_project_metadata("gdcc", "last_build_time", last_build_time)
    else:
        last_build_time = int(saved_time)
    var status_timer := Timer.new()
    status_timer.wait_time = 1.0
    status_timer.autostart = true
    status_timer.timeout.connect(_refresh_diagnostics_module)
    status_timer.timeout.connect(_poll_build_activation)
    add_child(status_timer)
    EditorInterface.get_resource_filesystem().filesystem_changed.connect(_on_build_filesystem_changed)
    _refresh_diagnostics_module()


func can_start_action() -> bool:
    return _busy_count == 0 and _pending_activation.is_empty()


func is_building() -> bool:
    return _build_in_progress or not _pending_activation.is_empty()


func _refresh_diagnostics_module() -> void:
    var module_id := "" if _service == null else _service.get_diag_module_id()
    if module_id != diagnostics_module_id:
        diagnostics_module_id = module_id
        diagnostics_module_changed.emit(module_id)
    if _service == null or not _service.is_diag_ready() \
            or _version_endpoint != [_service.rpc_host(), _service.rpc_port()]:
        _version_endpoint.clear()
        server_version = "Not connected"
        available_c_compiler_kinds.clear()
        _compiler_warning = ""
    state_changed.emit()
    if _service != null and _service.is_diag_ready() and not _version_busy \
            and (_version_endpoint.is_empty() or Time.get_ticks_msec() - _server_refresh_msec >= 5000):
        refresh_server_version()


func build_warning() -> String:
    if _service == null or not _service.is_diag_ready():
        return "Compilation server is not connected."
    if _version_endpoint != [_service.rpc_host(), _service.rpc_port()]:
        return "Checking compilation backends..."
    if _compiler_warning != "":
        return _compiler_warning
    if available_c_compiler_kinds.is_empty():
        return "No compilation backends are available."
    var selected := str(ProjectSettings.get_setting(SETTING_C_COMPILER_KIND, "AUTO"))
    if selected != "AUTO" and selected not in available_c_compiler_kinds:
        return "Selected compilation backend is unavailable."
    return ""


func status_text() -> String:
    var status: Dictionary = {} if _service == null else _service.diag_channel_status()
    var last_round := int(status.get("last_round_msec", 0))
    var round_text := "Never" if last_round == 0 else relative_time_text(int((Time.get_ticks_msec() - last_round) / 1000.0))
    return "%s | Diagnostics: %s | Build: %s" \
            % ["Connected" if status.get("ready", false) else "Disconnected", round_text, last_build_age_text()]


func last_build_age_text() -> String:
    return "Never" if last_build_time <= 0 else relative_time_text(int(Time.get_unix_time_from_system()) - last_build_time)


# Both views use floored half-units, except 10-59 minutes which use whole minutes.
static func relative_time_text(elapsed_seconds: int) -> String:
    var seconds := maxi(0, elapsed_seconds)
    if seconds < 60:
        return "%ds ago" % seconds
    var unit_seconds := 60
    var unit := "min"
    if seconds >= 86400:
        unit_seconds = 86400
        unit = "d"
    elif seconds >= 3600:
        unit_seconds = 3600
        unit = "h"
    var count := int(seconds / float(unit_seconds))
    var half := ".5" if seconds % unit_seconds >= unit_seconds * 0.5 else ""
    if seconds >= 600 and seconds < 3600:
        half = ""
    return "%d%s%s ago" % [count, half, unit]


func _exit_tree() -> void:
    var requests_in_flight := _busy_count > 0
    # GDScript has no try/finally: if the plugin is disabled mid-request, the pending
    # coroutines are dropped with this node and `_end_busy` never runs — drain the residual
    # count through the coordinator here or the editor would stay in full-speed mode.
    if _busy_count > 0:
        if _report_busy.is_valid():
            _report_busy.call(-_busy_count)
        _busy_count = 0
    if _version_busy:
        if _report_busy.is_valid():
            _report_busy.call(-1)
        _version_busy = false
    # A pending request can still mutate the copy after teardown. Keep its reservation until
    # the server stops rather than letting another editor acquire the same build directory.
    if requests_in_flight:
        return
    # The frame-pumped client has left the tree. Verify the server-side reservation before
    # bounded blocking cleanup: this endpoint may now belong to a restarted server.
    for entry in _owned_copies:
        var host := str(entry["host"])
        var port := int(entry["port"])
        var owner_id := str(entry["module_id"]) + "_owner"
        var owner := _call_rpc_blocking(host, port, "module.get", {"moduleId": owner_id})
        if not owner.has("result") or str(owner["result"]["moduleName"]) != _compile_owner_token:
            continue
        if entry["copy_created"]:
            var deleted := _call_rpc_blocking(host, port, "module.delete", {"moduleId": entry["module_id"]})
            if not deleted.has("result") and int(deleted.get("error", {}).get("code", 0)) != ERR_MODULE_NOT_FOUND:
                continue
        _call_rpc_blocking(host, port, "module.delete", {"moduleId": owner_id})
    _owned_copies.clear()


func _log(message: String) -> void:
    log_message.emit(message)


func _log_error(action: String, rpc: Dictionary) -> void:
    var error: Dictionary = rpc["error"]
    _log(action + " failed [" + str(error["code"]) + "]: " + str(error["message"]))


# Busy bookkeeping for the editor-idle liveness mitigation; nesting-safe via a counter. The
# actual global flag write happens in the plugin's coordinator; this node only reports deltas
# and publishes state for both views. Action buttons are disabled while busy so re-entrant Build
# presses cannot race `_current_task_id`.
func _begin_busy() -> void:
    _busy_count += 1
    if _report_busy.is_valid():
        _report_busy.call(1)
    state_changed.emit()


func _end_busy() -> void:
    _busy_count = maxi(_busy_count - 1, 0)
    if _report_busy.is_valid():
        _report_busy.call(-1)
    state_changed.emit()


# Bounded RPC for teardown, where the frame-pumped client can no longer send. Failure keeps
# the reservation intact; an unknown response must never authorize deletion of another owner.
func _call_rpc_blocking(host: String, port: int, method: String, params: Dictionary) -> Dictionary:
    var http := HTTPClient.new()
    if http.connect_to_host(host, port) != OK:
        return {}
    var deadline := Time.get_ticks_msec() + 2000
    while http.get_status() == HTTPClient.STATUS_CONNECTING or http.get_status() == HTTPClient.STATUS_RESOLVING:
        http.poll()
        if Time.get_ticks_msec() >= deadline:
            return {}
        OS.delay_msec(10)
    if http.get_status() != HTTPClient.STATUS_CONNECTED:
        return {}
    var body := JSON.stringify({
        "jsonrpc": "2.0", "id": 1, "method": method, "params": params,
    })
    if http.request(HTTPClient.METHOD_POST, "/rpc", ["Content-Type: application/json"], body) != OK:
        return {}
    while http.get_status() == HTTPClient.STATUS_REQUESTING:
        http.poll()
        if Time.get_ticks_msec() >= deadline:
            return {}
        OS.delay_msec(10)
    if not http.has_response() or http.get_response_code() != 200:
        return {}
    var response := PackedByteArray()
    while http.get_status() == HTTPClient.STATUS_BODY:
        http.poll()
        response.append_array(http.read_response_body_chunk())
        if Time.get_ticks_msec() >= deadline:
            return {}
        OS.delay_msec(10)
    var parsed: Variant = JSON.parse_string(response.get_string_from_utf8())
    return parsed if parsed is Dictionary else {}


## Points the project RPC client at the service's effective endpoint — the single source the
## diagnostics channel follows (the per-project `gdcc/server/*` settings reach it through
## the plugin's settings-changed relay). Falls back to the raw configured pair only when the
## service is unavailable.
func _apply_endpoint() -> void:
    if _service != null:
        _client.host = _service.rpc_host()
        _client.port = _service.rpc_port()
    else:
        _client.host = str(ProjectSettings.get_setting(SETTING_SERVER_HOST, "127.0.0.1")).strip_edges()
        _client.port = int(ProjectSettings.get_setting(SETTING_SERVER_PORT, 6099))


func ping() -> void:
    if not can_start_action():
        return
    _apply_endpoint()
    _begin_busy()
    var rpc: Dictionary = await _client.ping().completed
    _end_busy()
    if rpc["ok"]:
        _log("ping: " + str(rpc["result"]))
    else:
        _log_error("ping", rpc)


func create_module(module_id: String) -> void:
    if not can_start_action() or module_id.strip_edges().is_empty():
        return
    _apply_endpoint()
    module_id = module_id.strip_edges()
    _begin_busy()
    var rpc: Dictionary = await _client.create_module(module_id, module_id).completed
    _end_busy()
    if rpc["ok"]:
        _log("module created: " + module_id)
    else:
        _log_error("create module", rpc)


func analyze(module_id: String) -> void:
    if not can_start_action() or module_id.strip_edges().is_empty():
        return
    _apply_endpoint()
    module_id = module_id.strip_edges()
    _begin_busy()
    # Pass every argument explicitly: gdcc registers no ClassDB default values
    # so interpreted callers cannot omit them.
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


# Godot's filename validation does not remove control characters, which Windows rejects.
func _project_filename(project_name: String) -> String:
    var validated := project_name.validate_filename()
    var safe := ""
    for index in range(validated.length()):
        var code := validated.unicode_at(index)
        safe += "_" if code < 32 or code == 127 else validated[index]
    return safe


# Stable across editor restarts, distinct for projects with equal or filename-colliding names.
func _compile_module_id(project_name: String) -> String:
    var identity := ProjectSettings.globalize_path("res://") + "\n" + project_name
    return COMPILE_MODULE_PREFIX + _project_filename(project_name).left(32) + "_" + identity.sha256_text().left(16)


# Creates (or refreshes) the compile copy of the diagnostics module and points the copy's
# build directory at its own `.godot/gdcc/<copy>/<endpoint>` host dir. Returns the copy's module id, or
# "" after logging why the build cannot proceed. `call_rpc` is used instead of typed
# wrappers because the installed compiled extension may predate them (the same reason the old
# auto setup avoided `get_compile_options`).
func _prepare_compile_copy(source_module_id: String, project_name: String) -> String:
    var copy_module_id := _compile_module_id(project_name)
    var owner_id := copy_module_id + "_owner"
    # A separate, never-compiled module reserves this id without waiting on the copy's gate.
    # Atomic module.create admits one editor; a server restart invalidates old local ownership.
    var claimed: Dictionary = await _client.create_module(owner_id, _compile_owner_token).completed
    var new_claim: bool = claimed["ok"]
    if not new_claim:
        if int(claimed["error"]["code"]) != ERR_MODULE_ALREADY_EXISTS:
            _log_error("reserve compile module", claimed)
            return ""
        var owner: Dictionary = await _client.call_rpc("module.get", {"moduleId": owner_id}).completed
        if not owner["ok"] or str(owner["result"]["moduleName"]) != _compile_owner_token:
            _log("build skipped: this project's compile module is owned by another editor; close that editor or restart the gdcc server after an abnormal exit")
            return ""
    var owned_entry: Dictionary = {}
    for entry in _owned_copies:
        if entry["module_id"] == copy_module_id and entry["host"] == _client.host and entry["port"] == _client.port:
            owned_entry = entry
    if owned_entry.is_empty():
        owned_entry = {"host": _client.host, "port": _client.port, "module_id": copy_module_id, "copy_created": false}
        _owned_copies.append(owned_entry)
    if new_claim:
        owned_entry["copy_created"] = false
    var copy_params := {"sourceModuleId": source_module_id, "newModuleId": copy_module_id,
            "newModuleName": project_name}
    var copied: Dictionary = await _client.call_rpc("module.copy", copy_params).completed
    if not copied["ok"]:
        var code: int = int(copied["error"]["code"])
        if code == ERR_METHOD_NOT_FOUND:
            _log("compile failed: this gdcc server predates module.copy; upgrade the server")
            return ""
        if code == ERR_MODULE_ALREADY_EXISTS:
            if not owned_entry["copy_created"]:
                _log("build skipped: an unowned compile module already exists; restart the gdcc server to clear it")
                return ""
            # Replace only this owner's previous snapshot; never adopt a foreign crash remnant.
            var deleted: Dictionary = await _client.delete_module(copy_module_id).completed
            if not deleted["ok"] and int(deleted["error"]["code"]) == ERR_MODULE_BUSY \
                    and _current_task_id > 0 \
                    and _current_task_host == _client.host and _current_task_port == _client.port:
                # The previous compile (started by this manager on THIS endpoint, e.g. its poll
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
            owned_entry["copy_created"] = false
            copied = await _client.call_rpc("module.copy", copy_params).completed
        if not copied["ok"]:
            _log_error("copy diagnostics module", copied)
            return ""
    # `_client.host/port` are stable for the whole flow: `_apply_endpoint` only runs from
    # busy-gated button handlers (disabled while this flow is in flight) and Cancel now uses
    # its own pinned client instead of mutating the shared one.
    owned_entry["copy_created"] = true
    if str(copied["result"]["moduleName"]) != project_name:
        _log("build failed: the gdcc server does not support named module copies; upgrade the server")
        return ""
    # options.set replaces the whole snapshot, so fetch the full options.get shape and edit
    # only build settings. The diagnostics module's options must remain unchanged; the copy's
    # projectPath must be exclusive (server concurrency contract: no shared build dirs).
    var fetched: Dictionary = await _client.call_rpc("options.get", {"moduleId": copy_module_id}).completed
    if not fetched["ok"]:
        _log_error("get options", fetched)
        return ""
    var compile_options: Dictionary = fetched["result"]
    # Different servers have independent reservations and no cross-process build lock. Give
    # each endpoint a stable subdirectory, including when an old endpoint's task still runs.
    var endpoint := _client.host + "\n" + str(_client.port)
    var project_path: String = ProjectSettings.globalize_path(
            "res://.godot/gdcc/" + copy_module_id + "/" + endpoint.sha256_text().left(16))
    compile_options["projectPath"] = project_path
    compile_options["optimizationLevel"] = _build_options["optimizationLevel"]
    compile_options["strictMode"] = _build_options["strictMode"]
    compile_options["cCompilerKind"] = _build_options["cCompilerKind"]
    var applied: Dictionary = await _client.call_rpc(
            "options.set", {"moduleId": copy_module_id, "compileOptions": compile_options}).completed
    if not applied["ok"]:
        _log_error("set options", applied)
        return ""
    _log("compile copy ready: " + copy_module_id + " (projectPath: " + project_path + ")")
    return copy_module_id


# Build snapshots the service's private diagnostics module, kept
# continuously in sync by the reconciler into a reserved, project-scoped copy, then compiles the
# copy. The diagnostics module never hosts a compile, so its module gate stays free for
# editor analysis traffic while the native build runs.
func build_project() -> void:
    if not can_start_action():
        return
    _apply_endpoint()
    if _service == null or not _service.is_diag_ready():
        _log("build skipped: diagnostics channel is not ready yet")
        return
    var warning := build_warning()
    if warning != "":
        _log("build skipped: " + warning)
        return
    # `_apply_endpoint` sources the client endpoint from the service itself, so the compile
    # copy always targets the server that owns the diagnostics module. Its separate reservation
    # prevents the -32001 replace path from touching another editor's build.
    var source_module_id: String = _service.get_diag_module_id()
    if source_module_id == "":
        _log("build skipped: diagnostics module id is not available yet")
        return
    var project_name := str(ProjectSettings.get_setting("application/config/name", "")).strip_edges()
    if project_name == "":
        _log("build skipped: set the project name in Project Settings (application/config/name)")
        return
    var level := str(ProjectSettings.get_setting(SETTING_OPTIMIZATION_LEVEL, "DEBUG"))
    var strict: Variant = ProjectSettings.get_setting(SETTING_STRICT_MODE, false)
    if level not in ["DEBUG", "RELEASE"] or not strict is bool:
        _log("build skipped: invalid gdcc/build project settings")
        return
    # Freeze user choices before the first await; edits during a build affect the next one.
    var kind := str(ProjectSettings.get_setting(SETTING_C_COMPILER_KIND, "AUTO"))
    _build_options = {"optimizationLevel": level, "strictMode": strict,
            "cCompilerKind": null if kind == "AUTO" else kind}
    _build_in_progress = true
    _begin_busy()
    var module_id: String = await _prepare_compile_copy(source_module_id, project_name)
    if module_id == "":
        _end_build()
        return
    var started: Dictionary = await _client.start_compile(module_id).completed
    if not started["ok"]:
        _end_build()
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
            _end_build()
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
            if state == "SUCCEEDED" and task["result"] != null:
                # Deploy this task's frozen result, never a later module result or stale files.
                await _deploy_build_result(task["result"], project_name)
            if _current_task_id == task_id:
                _current_task_id = -1
            _end_build()
            return
        await get_tree().create_timer(0.25).timeout
    _end_build()
    # The task keeps running server-side and `_current_task_id` is deliberately kept: pressing
    # Build again cancels it through the stale-copy busy path.
    _log("build poll timed out (task " + str(task_id) + " still running; press Cancel, or Build again to cancel and replace it)")


func _end_build() -> void:
    _build_in_progress = false
    _end_busy()


func _deploy_build_result(result: Dictionary, project_name: String) -> bool:
    if str(result.get("outcome", "")) != "SUCCESS":
        _log("build deployment failed: compile result is not successful")
        return false
    # Open excluded addon tabs are admitted for diagnostics, but their native classes already
    # belong to the resident editor extension and must never be registered by a project build.
    for source_path in result["sourcePaths"]:
        if str(source_path).begins_with("res://addons/gdcc/"):
            _log("build deployment failed: editor addon sources are included; close those tabs or restore sync exclusions")
            return false
    var options: Dictionary = result["compileOptions"]
    var target_parts := str(options["targetPlatform"]).split("_", true, 1)
    var platform_features := {"WINDOWS": "windows", "LINUX": "linux", "MACOS": "macos",
            "ANDROID": "android", "WEB": "web"}
    var architecture_features := {"X86_64": "x86_64", "AARCH64": "arm64",
            "RISCV64": "rv64", "WASM32": "wasm32"}
    var library_extensions := {"windows": "dll", "linux": "so", "macos": "dylib",
            "android": "so", "web": "wasm"}
    if target_parts.size() != 2 or not platform_features.has(target_parts[0]) \
            or not architecture_features.has(target_parts[1]):
        _log("build deployment failed: unsupported target " + str(options["targetPlatform"]))
        return false
    var platform: String = platform_features[target_parts[0]]
    var architecture: String = architecture_features[target_parts[1]]
    var level := str(options["optimizationLevel"]).to_lower()
    if level not in ["debug", "release"]:
        _log("build deployment failed: unsupported optimization level " + level)
        return false
    var library_source := ""
    for artifact in result["artifacts"]:
        var path := str(artifact).replace("\\", "/")
        if path.get_extension().to_lower() == library_extensions[platform]:
            if library_source != "":
                _log("build deployment failed: compile result contains multiple libraries")
                return false
            library_source = path
    if library_source == "" or not FileAccess.file_exists(library_source):
        _log("build deployment failed: compiled library is not accessible on this editor's filesystem")
        return false
    var library_hash := FileAccess.get_sha256(library_source)
    if library_hash == "":
        _log("build deployment failed: cannot read compiled library")
        return false
    # A fresh path defeats dyld's same-path image cache and never overwrites a mapped library.
    var basename := _project_filename(project_name) + "_" + platform + "_" + level + "_" + architecture \
            + "-" + library_hash.left(16)
    var library_path := "res://bin/" + ("" if platform == "windows" or platform == "web" else "lib") \
            + basename + "." + str(library_extensions[platform])
    var config := ConfigFile.new()
    if FileAccess.file_exists(BUILD_EXTENSION_PATH):
        var loaded := config.load(BUILD_EXTENSION_PATH)
        var managed: Variant = config.get_value("configuration", "gdcc_managed", false)
        if loaded != OK or not managed is bool or not managed:
            _log("build deployment failed: refusing to overwrite an unmanaged or invalid " + BUILD_EXTENSION_PATH)
            return false
    var aliases_value: Variant = config.get_value("configuration", "gdcc_library_aliases", PackedStringArray())
    var owned_value: Variant = config.get_value("configuration", "gdcc_libraries", PackedStringArray())
    if not aliases_value is PackedStringArray or not owned_value is PackedStringArray:
        _log("build deployment failed: invalid library ownership/alias metadata in " + BUILD_EXTENSION_PATH)
        return false
    config.set_value("configuration", "entry_symbol", "gdextension_entry")
    config.set_value("configuration", "compatibility_minimum", "4.5")
    config.set_value("configuration", "reloadable", true)
    config.set_value("configuration", "gdcc_managed", true)
    var library_key := platform + "." + architecture
    var legacy_level := "debug" if OS.has_feature("debug") else "release"
    var legacy_active_key := platform + "." + legacy_level + "." + architecture
    var previous_active_library := str(config.get_value("libraries", legacy_active_key,
            config.get_value("libraries", library_key, "")))
    # More-specific legacy mode tags would override the new OS.arch entry. Keep existing
    # generic targets; otherwise prefer the editor's prior mode when merging old entries.
    if config.has_section("libraries"):
        for key in config.get_section_keys("libraries"):
            var tags := key.split(".")
            if tags.size() == 3 and tags[1] in ["debug", "release"]:
                var target_key := tags[0] + "." + tags[2]
                if not config.has_section_key("libraries", target_key):
                    var preferred_key := tags[0] + "." + legacy_level + "." + tags[2]
                    config.set_value("libraries", target_key, config.get_value("libraries", preferred_key,
                            config.get_value("libraries", key)))
                config.erase_section_key("libraries", key)
    config.set_value("libraries", library_key, library_path)
    if config.has_section_key("configuration", "gdcc_library_aliases"):
        config.erase_section_key("configuration", "gdcc_library_aliases")
    var owned_libraries: PackedStringArray = owned_value
    var retained_libraries := PackedStringArray()
    var obsolete_libraries := PackedStringArray()
    for owned_path in owned_libraries:
        if FileAccess.file_exists(owned_path):
            retained_libraries.append(owned_path)
            var referenced := false
            for key in config.get_section_keys("libraries"):
                referenced = referenced or str(config.get_value("libraries", key)) == owned_path
            if not referenced:
                obsolete_libraries.append(owned_path)
    if not retained_libraries.has(library_path):
        retained_libraries.append(library_path)
    config.set_value("configuration", "gdcc_libraries", retained_libraries)
    var filesystem := EditorInterface.get_resource_filesystem()
    var scan_deadline := Time.get_ticks_msec() + 30000
    while filesystem.is_scanning() and Time.get_ticks_msec() < scan_deadline:
        await get_tree().process_frame
    if filesystem.is_scanning():
        _log("build deployment failed: editor filesystem scan did not finish")
        return false
    var error := DirAccess.make_dir_recursive_absolute(ProjectSettings.globalize_path("res://bin"))
    if error != OK:
        _log("build deployment failed: cannot create bin (error " + str(error) + ")")
        return false
    var library_temp := ProjectSettings.globalize_path(library_path + ".tmp")
    if not FileAccess.file_exists(library_path):
        error = DirAccess.copy_absolute(library_source, library_temp)
        if error == OK:
            error = DirAccess.rename_absolute(library_temp, ProjectSettings.globalize_path(library_path))
    elif FileAccess.get_sha256(library_path) != library_hash:
        _log("build deployment failed: hash-named library has unexpected contents: " + library_path)
        return false
    if error == OK:
        # Windows rename deletes an existing destination first; do not use it for the descriptor.
        error = config.save(BUILD_EXTENSION_PATH)
    if error != OK:
        DirAccess.remove_absolute(library_temp)
        _log("build deployment failed: cannot publish library/metadata (error " + str(error) + ")")
        return false
    _log("build deployed: " + library_path + " (" + BUILD_EXTENSION_PATH + ")")
    _pending_activation = {"project_name": project_name, "target": platform + "." + architecture,
            "native": OS.has_feature(platform) and OS.has_feature(architecture),
            "reload": GDExtensionManager.is_extension_loaded(BUILD_EXTENSION_PATH) \
                    and previous_active_library != library_path,
            "obsolete": obsolete_libraries, "generation": _filesystem_generation,
            "deadline": Time.get_ticks_msec() + 30000}
    state_changed.emit()
    # Finish the compile coroutine before scanning: membership changes reload all GDScripts
    # and cancel their suspended executions. Godot, not the plugin, persists extension_list.cfg.
    _poll_build_activation.call_deferred()
    return true


func _poll_build_activation() -> void:
    if _pending_activation.is_empty():
        return
    if Time.get_ticks_msec() >= int(_pending_activation["deadline"]):
        _pending_activation.clear()
        state_changed.emit()
        _log("build activation pending: filesystem scan timed out; restart the editor to load the deployed extension")
        return
    # is_scanning() can turn false before the worker is joined. A rejected scan is retried
    # on the timer; only filesystem_changed confirms main-thread extension reconciliation.
    var filesystem := EditorInterface.get_resource_filesystem()
    if not filesystem.is_scanning():
        filesystem.scan()


func _on_build_filesystem_changed() -> void:
    _filesystem_generation += 1
    if _pending_activation.is_empty() or _filesystem_generation <= int(_pending_activation["generation"]):
        return
    var filesystem := EditorInterface.get_resource_filesystem()
    if filesystem.get_file_type(BUILD_EXTENSION_PATH) != "GDExtension":
        return
    var activation := _pending_activation
    _pending_activation = {}
    state_changed.emit()
    if not activation["native"]:
        _log("build installed for " + str(activation["target"]) + "; not the current editor target")
    elif not GDExtensionManager.is_extension_loaded(BUILD_EXTENSION_PATH):
        _log("build activation failed: Godot did not load the extension; check engine errors and restart the editor")
        return
    else:
        if activation["reload"]:
            var status := GDExtensionManager.reload_extension(BUILD_EXTENSION_PATH)
            if status != GDExtensionManager.LOAD_STATUS_OK:
                _log("build activation failed: extension reload status " + str(status) + "; restart the editor")
                return
        _log("build enabled: " + str(activation["project_name"]))
    last_build_time = int(Time.get_unix_time_from_system())
    EditorInterface.get_editor_settings().set_project_metadata("gdcc", "last_build_time", last_build_time)
    state_changed.emit()
    for obsolete in activation["obsolete"]:
        if str(obsolete).get_base_dir() == "res://bin":
            var error := DirAccess.remove_absolute(ProjectSettings.globalize_path(str(obsolete)))
            if error != OK:
                _log("build cleanup deferred: " + str(obsolete) + " (error " + str(error) + ")")


func cancel_build() -> void:
    if _current_task_id < 0:
        _log("cancel skipped: no compile task has been started from this project manager")
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


# Plugin-load entry point (called once after this node enters the tree): ensures
# the compile service is reachable (launching it first when a launch command is configured),
# then announces the service's private diagnostics module to its views.
# Build copies that module on demand, so no additional diagnostics module is created here.
# Failures are only logged; the toolbar disables Build until the channel and backends are ready.
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
    # so the output can announce the actual diagnostics module.
    var deadline_msec: int = Time.get_ticks_msec() + 10000
    while Time.get_ticks_msec() < deadline_msec:
        if _service != null:
            var diag_module_id: String = _service.get_diag_module_id()
            if diag_module_id != "":
                _refresh_diagnostics_module()
                _log("inspecting diagnostics module: " + diag_module_id)
                return
        await get_tree().create_timer(0.5).timeout
    _log("diagnostics module not announced yet; waiting for automatic synchronization")


## Server metadata uses its own client so menu queries cannot retarget a live build.
func refresh_server_version() -> void:
    if _version_busy or _service == null or not _service.is_diag_ready():
        return
    var endpoint := [_service.rpc_host(), _service.rpc_port()]
    if endpoint != _version_endpoint:
        server_version = "Not connected"
        available_c_compiler_kinds.clear()
        state_changed.emit()
    var version_client := GdccRpcClient.new()
    add_child(version_client)
    version_client.host = endpoint[0]
    version_client.port = endpoint[1]
    _version_busy = true
    _server_refresh_msec = Time.get_ticks_msec()
    if _report_busy.is_valid():
        _report_busy.call(1)
    # Script reload cancels suspended awaits; name-bound signal callbacks keep the flight live.
    version_client.call_rpc("server.info", {}).completed.connect(
            Callable(self, "_on_server_info_received").bind(version_client, endpoint), CONNECT_ONE_SHOT)


func _on_server_info_received(response: Dictionary, version_client: GdccRpcClient, endpoint: Array) -> void:
    version_client.call_rpc("server.availableCCompilerKinds", {}).completed.connect(
            Callable(self, "_on_compiler_kinds_received").bind(version_client, endpoint, response), CONNECT_ONE_SHOT)


func _on_compiler_kinds_received(compilers: Dictionary, version_client: GdccRpcClient,
        endpoint: Array, response: Dictionary) -> void:
    version_client.queue_free()
    _version_busy = false
    if _report_busy.is_valid():
        _report_busy.call(-1)
    if _service == null or not _service.is_diag_ready() \
            or endpoint != [_service.rpc_host(), _service.rpc_port()]:
        _version_endpoint.clear()
        server_version = "Not connected"
        available_c_compiler_kinds.clear()
        _compiler_warning = ""
        state_changed.emit()
        return
    _version_endpoint = endpoint
    if response["ok"] and response["result"] is Dictionary:
        var info: Dictionary = response["result"]
        server_version = str(info.get("version", "Unknown")) + " (" \
                + str(info.get("branch", "")) + " " + str(info.get("commit", "")) + ")"
    elif response["ok"]:
        server_version = "Unavailable: invalid server info"
    else:
        server_version = "Unavailable: " + str(response["error"]["message"])
    available_c_compiler_kinds.clear()
    _compiler_warning = ""
    if not compilers["ok"]:
        _compiler_warning = "Could not query compilation backends."
    elif not compilers["result"] is Array:
        _compiler_warning = "Invalid compilation backend response."
    else:
        for kind in compilers["result"]:
            if not kind is String or kind.is_empty() or kind == "AUTO":
                available_c_compiler_kinds.clear()
                _compiler_warning = "Invalid compilation backend response."
                break
            if kind not in available_c_compiler_kinds:
                available_c_compiler_kinds.append(kind)
    state_changed.emit()

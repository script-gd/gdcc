@tool
extends Node

## On-demand launcher for the `gdcc serve` compile service (implementation plan §3.7) —
## interpreted GDScript on purpose: the dock needs it before the gdcc language is registered,
## so it can never be a compile target.
##
## OWNERSHIP INVARIANT: only processes spawned by this session's own `OS.create_process` calls
## (recorded in `_spawned`) are ever shut down. An endpoint that already answers a probe is
## an EXTERNAL service: connected to, never killed. If the editor crashes, the orphan is
## "adopted" by the next session's probe as an external service, so nothing leaks or
## accumulates.
##
## The launch command comes from EditorSettings `gdcc/server/launch_command` (machine-local
## developer configuration; empty = never auto-launch, pure passive-connect behavior). It is a
## user-supplied local command executed with editor privileges — the trust boundary is the
## user's own configuration, the plugin never generates command text by itself.

const SETTING_LAUNCH_COMMAND := "gdcc/server/launch_command"
const PROBE_BUDGET_MSEC := 200
const READY_TIMEOUT_MSEC := 15000
const SHUTDOWN_BUDGET_MSEC := 2000

# Ensure-flight phases (queue head only): 0 = probing the endpoint for an existing service,
# 1 = spawned (or re-attached to our earlier spawn), waiting for the port to accept.
const PHASE_PROBE := 0
const PHASE_WAIT_READY := 1

var _report_busy: Callable
# Ownership records for every process spawned this session, one per endpoint
# ({pid: int, host: String, port: int}). A session can serve more than one endpoint (the dock
# endpoint is user-editable), and EVERY spawned process is shut down on teardown — tracking
# only the latest would orphan the earlier ones.
var _spawned: Array = []
# Single-flight queue of ensure requests; only the head runs. Entry shape:
# {host: String, port: int, waiters: Array[Callable], phase: int, deadline: int,
#  peer: StreamPeerTCP, pid: int}
var _ensure_queue: Array = []
# Whether this flight currently holds a +1 on the plugin's low-power busy coordinator.
var _flight_busy: bool = false
# Set while `_finish_head` runs its callbacks: a callback that reentrantly calls
# `ensure_running` must only enqueue (the loop below starts the next head after all
# callbacks), otherwise the same head would be started twice mid-flight.
var _finishing: bool = false


func setup(busy_reporter: Callable) -> void:
    _report_busy = busy_reporter


## Ensures a compile service answers on host:port, then calls
## `on_ready.call(err: int, spawned_by_us: bool)`. Fully asynchronous (driven by `_process`,
## never blocks the editor main thread). Spawned-by-us is per CALL: only the caller whose
## request actually created the process sees `true`.
func ensure_running(host: String, port: int, on_ready: Callable) -> void:
    if not _ensure_queue.is_empty():
        var head: Dictionary = _ensure_queue[0]
        if str(head["host"]) == host and int(head["port"]) == port:
            # Same endpoint as the in-flight ensure: share the single flight.
            (head["waiters"] as Array).append(on_ready)
            return
    var was_idle := _ensure_queue.is_empty()
    _ensure_queue.append({
        "host": host,
        "port": port,
        "waiters": [on_ready],
        "phase": PHASE_PROBE,
        "deadline": 0,
        "peer": null,
        "pid": -1,
    })
    if was_idle and not _finishing:
        _start_head()


## Coroutine convenience wrapper for interpreted callers (the dock); the compiled service uses
## the Callable form directly. Returns `[err, spawned_by_us]`.
func ensure_running_async(host: String, port: int) -> Array:
    var box := {"done": false, "err": FAILED, "spawned": false}
    ensure_running(host, port, func(err: int, spawned: bool) -> void:
        box["done"] = true
        box["err"] = err
        box["spawned"] = spawned)
    while not box["done"]:
        await get_tree().process_frame
    return [int(box["err"]), bool(box["spawned"])]


## Last step of the plugin's `_exit_tree`: gracefully stops ONLY the service process this
## session spawned. The frame pump may already be stopped at this point, so the wait is a
## bounded non-blocking `poll()` + wall-clock deadline loop (the single allowed exemption from
## the editor's no-blocking rule; `OS.delay_msec` is still forbidden). The `server.shutdown`
## RPC response IS the acknowledgment — the JVM's shutdown hook finishes the graceful tail in
## the background and the editor does not wait for it (in-flight compiles are never cut off).
func shutdown_owned() -> void:
    # Shut down EVERY process this session spawned (the dock endpoint is user-editable, so more
    # than one may exist), each under the same RPC-then-guarded-kill rule. The graceful-RPC
    # budget is shared across all records (healthy localhost round trips answer in
    # milliseconds; only a wedged server burns its slice), and a record whose share is already
    # gone skips straight to its guarded kill with a small fixed probe slice — records are
    # never dropped unhandled, so a wedged first server can never orphan the rest.
    var deadline := Time.get_ticks_msec() + SHUTDOWN_BUDGET_MSEC
    var total := _spawned.size()
    var index := 0
    for record: Dictionary in _spawned:
        index += 1
        var pid := int(record["pid"])
        if pid <= 0 or not OS.is_process_running(pid):
            continue
        var host := str(record["host"])
        var port := int(record["port"])
        var remaining: int = deadline - Time.get_ticks_msec()
        var err := ERR_CONNECTION_ERROR
        if remaining > 0:
            var left: int = total - index + 1
            err = _send_shutdown_rpc(host, port, maxi(remaining / left, 1))
        if err != OK:
            # The RPC was unreachable. Kill only when the recorded endpoint still accepts TCP —
            # that proves our service process is alive and holding the port; a recycled PID
            # belonging to an unrelated process must never be killed (risk register R19).
            var probe_budget: int = mini(PROBE_BUDGET_MSEC, maxi(0, deadline - Time.get_ticks_msec()) + 150)
            if _probe_port_open(host, port, probe_budget):
                OS.kill(pid)
            else:
                print("GDCC server launcher: spawned service (pid %d) unreachable and its port is closed; leaving it alone." % pid)
    _spawned.clear()


func _exit_tree() -> void:
    # A dropped plugin must not strand the coordinator's busy counter on a never-finished
    # flight; the callbacks simply never fire, matching every other mid-flight disable.
    if _flight_busy:
        _flight_busy = false
        _report_busy.call(-1)
    _ensure_queue.clear()


func _process(_delta: float) -> void:
    if _ensure_queue.is_empty():
        return
    var head: Dictionary = _ensure_queue[0]
    var now := Time.get_ticks_msec()
    if int(head["phase"]) == PHASE_PROBE:
        _poll_probe_phase(head, now)
    else:
        _poll_ready_phase(head, now)


func _start_head() -> void:
    _set_flight_busy(true)
    var head: Dictionary = _ensure_queue[0]
    var peer := StreamPeerTCP.new()
    head["peer"] = peer
    head["deadline"] = Time.get_ticks_msec() + PROBE_BUDGET_MSEC
    # Connection-refused surfaces asynchronously (status ERROR after a poll), not necessarily
    # as an immediate error return.
    peer.connect_to_host(str(head["host"]), int(head["port"]))


func _poll_probe_phase(head: Dictionary, now: int) -> void:
    var peer: StreamPeerTCP = head["peer"]
    if peer != null:
        peer.poll()
        if peer.get_status() == StreamPeerTCP.STATUS_CONNECTED:
            peer.disconnect_from_host()
            head["peer"] = null
            # External service: connectable before we spawned anything — never shut it down.
            _finish_head(OK, false)
            return
        if peer.get_status() == StreamPeerTCP.STATUS_CONNECTING and now < int(head["deadline"]):
            return  # probe still in flight within its budget
        peer.disconnect_from_host()
        head["peer"] = null
    # Nothing is listening. Without a configured command this is exactly the historical
    # passive behavior: the caller logs the connection failure.
    var command := _read_launch_command()
    if command == "":
        _finish_head(ERR_CANT_CONNECT, false)
        return
    var host := str(head["host"])
    var port := int(head["port"])
    # This session may already have spawned the service for this endpoint (still booting):
    # re-attach instead of spawning a second process.
    var existing := _find_spawned(host, port)
    if not existing.is_empty():
        var existing_pid := int(existing["pid"])
        if OS.is_process_running(existing_pid):
            head["pid"] = existing_pid
            _enter_ready_phase(head)
            return
        _remove_spawned(existing_pid)  # dead record: fall through to a fresh spawn
    # [err, pid]: keep the real failure reason (bad command text vs spawn failure) visible.
    var spawn_result: Array = _spawn(command, host, port)
    if int(spawn_result[0]) != OK:
        _finish_head(int(spawn_result[0]), false)
        return
    head["pid"] = int(spawn_result[1])
    _enter_ready_phase(head)


func _enter_ready_phase(head: Dictionary) -> void:
    head["phase"] = PHASE_WAIT_READY
    head["deadline"] = Time.get_ticks_msec() + READY_TIMEOUT_MSEC
    head["peer"] = null


func _poll_ready_phase(head: Dictionary, now: int) -> void:
    var pid := int(head["pid"])
    if pid > 0 and not OS.is_process_running(pid):
        printerr("GDCC server launcher: spawned compile service (pid %d) exited before accepting connections; check the launch command." % pid)
        _remove_spawned(pid)
        _finish_head(ERR_CANT_CONNECT, false)
        return
    if now >= int(head["deadline"]):
        if pid > 0 and OS.is_process_running(pid):
            OS.kill(pid)
            _remove_spawned(pid)
        printerr("GDCC server launcher: spawned compile service did not accept connections within %d ms; killed." % READY_TIMEOUT_MSEC)
        _finish_head(ERR_TIMEOUT, false)
        return
    var peer: StreamPeerTCP = head["peer"]
    if peer == null:
        peer = StreamPeerTCP.new()
        head["peer"] = peer
        peer.connect_to_host(str(head["host"]), int(head["port"]))
        return
    peer.poll()
    var status := peer.get_status()
    if status == StreamPeerTCP.STATUS_CONNECTED:
        peer.disconnect_from_host()
        head["peer"] = null
        _finish_head(OK, true)
    elif status != StreamPeerTCP.STATUS_CONNECTING:
        # Refused: the service is not listening yet; keep retrying until the deadline.
        peer.disconnect_from_host()
        head["peer"] = null


func _finish_head(err: int, spawned_by_us: bool) -> void:
    var head: Dictionary = _ensure_queue[0]
    _ensure_queue.remove_at(0)
    var waiters: Array = head["waiters"]
    # Callbacks run synchronously and may reentrantly call `ensure_running`; the guard makes
    # such calls enqueue-only, and the queue is re-examined only after all callbacks ran.
    _finishing = true
    for waiter: Callable in waiters:
        waiter.call(err, spawned_by_us)
    _finishing = false
    if _ensure_queue.is_empty():
        _set_flight_busy(false)
    else:
        _start_head()


## Low-power liveness: the editor idles without frames under `low_processor_usage_mode`, which
## would starve this node's `_process` polling. The plugin's coordinator is the single writer
## of the global flag; the launcher only reports +1 for the duration of a flight.
func _set_flight_busy(busy: bool) -> void:
    if busy == _flight_busy:
        return
    _flight_busy = busy
    _report_busy.call(1 if busy else -1)


## Returns `[err, pid]`: err == OK with the spawned pid, or the concrete failure (an empty
## command text is a configuration error, not a fork failure). The ownership record is kept
## per endpoint so a session that serves several endpoints still shuts every spawned process
## down.
func _spawn(command_template: String, host: String, port: int) -> Array:
    var command := command_template.replace("{host}", host).replace("{port}", str(port))
    var tokens := _tokenize_command(command)
    if tokens.is_empty():
        return [ERR_INVALID_PARAMETER, -1]
    var pid := OS.create_process(tokens[0], tokens.slice(1))
    if pid <= 0:
        printerr("GDCC server launcher: OS.create_process failed for the configured launch command (executable: %s)." % tokens[0])
        return [ERR_CANT_FORK, -1]
    _spawned.append({"pid": pid, "host": host, "port": port})
    print("GDCC server launcher: started compile service (pid %d) for %s:%d." % [pid, host, port])
    return [OK, pid]


func _find_spawned(host: String, port: int) -> Dictionary:
    for record: Dictionary in _spawned:
        if str(record["host"]) == host and int(record["port"]) == port:
            return record
    return {}


func _remove_spawned(pid: int) -> void:
    var i := 0
    while i < _spawned.size():
        if int((_spawned[i] as Dictionary)["pid"]) == pid:
            _spawned.remove_at(i)
        else:
            i += 1


## Command parsing: whitespace tokenization with double-quote grouping; no shell expansion, no
## pipes. The first token is the executable, the rest are arguments.
func _tokenize_command(command: String) -> PackedStringArray:
    var tokens := PackedStringArray()
    var current := ""
    var in_quotes := false
    var i := 0
    while i < command.length():
        var ch := command[i]
        if ch == "\"":
            in_quotes = not in_quotes
        elif (ch == " " or ch == "\t") and not in_quotes:
            if current != "":
                tokens.append(current)
                current = ""
        else:
            current += ch
        i += 1
    if current != "":
        tokens.append(current)
    return tokens


func _read_launch_command() -> String:
    var settings := EditorInterface.get_editor_settings()
    if settings == null or not settings.has_setting(SETTING_LAUNCH_COMMAND):
        return ""
    return str(settings.get_setting(SETTING_LAUNCH_COMMAND)).strip_edges()


## One-shot `server.shutdown` over a raw HTTPClient (the frame-pumped HTTPRequest of the RPC
## client cannot be relied on during `_exit_tree`). Returns OK only when the full 200 response
## — headers AND body — was read: the server contract is "exit strictly after the whole
## response is written", so a truncated read must count as failure (the guarded-kill fallback
## then decides), never as a successful shutdown.
func _send_shutdown_rpc(host: String, port: int, budget_msec: int) -> int:
    var http := HTTPClient.new()
    var err := http.connect_to_host(host, port)
    if err == OK:
        var deadline := Time.get_ticks_msec() + budget_msec
        while http.get_status() == HTTPClient.STATUS_CONNECTING or http.get_status() == HTTPClient.STATUS_RESOLVING:
            if Time.get_ticks_msec() >= deadline:
                err = ERR_TIMEOUT
                break
            http.poll()
        if err == OK and http.get_status() != HTTPClient.STATUS_CONNECTED:
            err = ERR_CANT_CONNECT
        if err == OK:
            err = http.request(
                    HTTPClient.METHOD_POST, "/rpc",
                    PackedStringArray(["Content-Type: application/json"]),
                    "{\"jsonrpc\":\"2.0\",\"method\":\"server.shutdown\",\"id\":1}")
        while err == OK and http.get_status() == HTTPClient.STATUS_REQUESTING:
            if Time.get_ticks_msec() >= deadline:
                err = ERR_TIMEOUT
                break
            http.poll()
        var body := PackedByteArray()
        while err == OK and http.get_status() == HTTPClient.STATUS_BODY:
            if Time.get_ticks_msec() >= deadline:
                err = ERR_TIMEOUT
                break
            http.poll()
            var chunk := http.read_response_body_chunk()
            if chunk.size() > 0:
                body.append_array(chunk)
        var expected := http.get_response_body_length()
        if err == OK and http.get_response_code() != 200:
            err = ERR_CONNECTION_ERROR
        if err == OK and expected >= 0 and body.size() < expected:
            err = ERR_CONNECTION_ERROR
    http.close()
    return err


## Bounded wall-clock probe used by the kill guard: true only when the endpoint accepts a TCP
## connection within the budget.
func _probe_port_open(host: String, port: int, budget_msec: int) -> bool:
    var peer := StreamPeerTCP.new()
    if peer.connect_to_host(host, port) != OK:
        return false
    var open := false
    var deadline := Time.get_ticks_msec() + budget_msec
    while Time.get_ticks_msec() < deadline:
        peer.poll()
        var status := peer.get_status()
        if status == StreamPeerTCP.STATUS_CONNECTED:
            open = true
            break
        if status != StreamPeerTCP.STATUS_CONNECTING:
            break
    peer.disconnect_from_host()
    return open

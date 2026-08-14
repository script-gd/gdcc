class_name SignalEmitConnectSmoke
extends Node

signal pinged
signal counted(value: int)
signal unused
signal one_shot_pinged
signal deferred_pinged

var last_count: int = 0
var handler_hits: int = 0
var one_shot_hits: int = 0
var deferred_hits: int = 0
var mixed_int: int = 0
var mixed_label: String = ""
var mixed_vec: Vector2 = Vector2.ZERO
var pinged_cb: Callable
var counted_cb: Callable
var mixed_cb: Callable
var one_shot_cb: Callable
var deferred_cb: Callable

func _on_pinged() -> void:
    handler_hits += 1

func _on_counted(value: int) -> void:
    handler_hits += 1
    last_count = value

func _on_one_shot() -> void:
    one_shot_hits += 1

func _on_deferred() -> void:
    deferred_hits += 1

func _on_unused(value, label, vec) -> void:
    mixed_int = int(value)
    mixed_label = String(label)
    mixed_vec = vec

func emit_none() -> void:
    pinged.emit()

func emit_declared(value: int) -> void:
    counted.emit(value)

func emit_heterogeneous(label: String, vec: Vector2) -> void:
    unused.emit(1, label, vec)

func connect_default() -> int:
    pinged_cb = _on_pinged
    counted_cb = _on_counted
    mixed_cb = _on_unused
    var counted_err = counted.connect(counted_cb)
    if counted_err != 0:
        return counted_err
    var mixed_err = unused.connect(mixed_cb)
    if mixed_err != 0:
        return mixed_err
    return pinged.connect(pinged_cb)

func connect_deferred() -> int:
    deferred_cb = _on_deferred
    return deferred_pinged.connect(deferred_cb, Object.CONNECT_DEFERRED)

func connect_one_shot() -> int:
    one_shot_cb = _on_one_shot
    return one_shot_pinged.connect(one_shot_cb, Object.CONNECT_ONE_SHOT)

func disconnect_default() -> void:
    pinged.disconnect(pinged_cb)

func fire_and_count() -> int:
    pinged.emit()
    return handler_hits

func fire_counted(value: int) -> int:
    counted.emit(value)
    return last_count

func fire_one_shot() -> int:
    one_shot_pinged.emit()
    return one_shot_hits

func fire_deferred() -> int:
    deferred_pinged.emit()
    return deferred_hits

func read_hits() -> int:
    return handler_hits

func read_one_shot_hits() -> int:
    return one_shot_hits

func read_deferred_hits() -> int:
    return deferred_hits

func read_mixed_int() -> int:
    return mixed_int

func read_mixed_label() -> String:
    return mixed_label

func read_mixed_vec() -> Vector2:
    return mixed_vec

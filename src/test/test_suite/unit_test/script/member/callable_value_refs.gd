class_name CallableValueRefsSmoke
extends Node

signal pinged
signal labeled(prefix: String)
signal unused

var hits: int = 0

static func build_label(prefix: String) -> String:
    return prefix + "-static"

func _on_pinged() -> void:
    hits += 1

func abs_via_callable(vec: Vector2) -> Vector2:
    var cb = vec.abs
    return cb.call()

func clear_via_callable(values: Array) -> int:
    var cb = values.clear
    cb.call()
    return values.size()

func static_via_callable(prefix: String) -> String:
    var cb = CallableValueRefsSmoke.build_label
    return cb.call(prefix)

func engine_static_via_callable(text: String) -> Variant:
    var cb = JSON.parse_string
    return cb.call(text)

func utility_via_callable(from_value: float, to_value: float, weight: float) -> float:
    var cb = lerp
    return cb.call(from_value, to_value, weight)

func connect_builtin_and_static(vec: Vector2) -> int:
    var abs_cb = vec.abs
    var static_cb = CallableValueRefsSmoke.build_label
    var utility_cb = print
    var handler_err = pinged.connect(_on_pinged)
    var abs_err = pinged.connect(abs_cb)
    var static_err = labeled.connect(static_cb)
    var print_err = unused.connect(utility_cb)
    if handler_err != 0 or abs_err != 0 or static_err != 0 or print_err != 0:
        return -1
    pinged.emit()
    labeled.emit("ok")
    unused.emit(1, "mixed", Vector2.ONE)
    return hits

func standalone_valid() -> bool:
    var static_cb = CallableValueRefsSmoke.build_label
    var engine_cb = JSON.parse_string
    var utility_cb = lerp
    return static_cb.is_valid() and engine_cb.is_valid() and utility_cb.is_valid()

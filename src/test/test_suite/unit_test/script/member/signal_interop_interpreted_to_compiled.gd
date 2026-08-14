class_name SignalInteropInterpretedToCompiledSmoke
extends Node

signal inbound_pinged

var pinged_hits: int = 0
var last_count: int = 0
var callable_hits: int = 0

func wire_pinged(sig: Signal) -> int:
    return sig.connect(_on_pinged)

func wire_counted(sig: Signal) -> int:
    return sig.connect(_on_counted)

func connect_inbound_callable(cb: Callable) -> int:
    return inbound_pinged.connect(cb)

func fire_inbound_callable() -> void:
    inbound_pinged.emit()

func get_callable_handler() -> Callable:
    return _on_callable

func _on_pinged() -> void:
    pinged_hits += 1

func _on_counted(value: int) -> void:
    last_count = value

func _on_callable() -> void:
    callable_hits += 1

func read_pinged_hits() -> int:
    return pinged_hits

func read_last_count() -> int:
    return last_count

func read_callable_hits() -> int:
    return callable_hits

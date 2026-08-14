class_name SignalInteropBidirectionalSmoke
extends Node

signal compiled_pinged
signal compiled_counted(value: int)

var from_interpreted_hits: int = 0
var last_interpreted_count: int = 0

func wire_interpreted(sig: Signal) -> int:
    return sig.connect(_on_from_interpreted)

func wire_interpreted_counted(sig: Signal) -> int:
    return sig.connect(_on_from_interpreted_counted)

func fire_compiled() -> void:
    compiled_pinged.emit()

func fire_compiled_counted(value: int) -> void:
    compiled_counted.emit(value)

func _on_from_interpreted() -> void:
    from_interpreted_hits += 1

func _on_from_interpreted_counted(value: int) -> void:
    last_interpreted_count = value

func read_from_interpreted_hits() -> int:
    return from_interpreted_hits

func read_last_interpreted_count() -> int:
    return last_interpreted_count

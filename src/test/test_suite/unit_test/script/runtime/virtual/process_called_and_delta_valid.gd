class_name ProcessVirtualRuntimeSmoke
extends Node

# The validation script reads these counters after a few engine-driven idle frames.
var process_count: int = 0
var last_process_delta: float = 0.0

func _process(delta: float) -> void:
    process_count = process_count + 1
    last_process_delta = delta

func get_process_count_value() -> int:
    return process_count

func get_last_process_delta_value() -> float:
    return last_process_delta

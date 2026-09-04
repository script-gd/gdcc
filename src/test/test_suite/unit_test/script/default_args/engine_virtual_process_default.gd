class_name DefaultArgsEngineVirtualProcess
extends Node

var process_count: int = 0
var delta_sum: float = 0.0

func _process(delta: float = 0.0) -> void:
	process_count += 1
	delta_sum += delta

func get_process_count_value() -> int:
	return process_count

func get_delta_sum_value() -> float:
	return delta_sum

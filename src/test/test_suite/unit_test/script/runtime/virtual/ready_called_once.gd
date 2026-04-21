class_name ReadyVirtualRuntimeSmoke
extends Node

# Deliberately rely on engine virtual dispatch instead of any manual trigger.
var ready_count: int = 0

func _ready() -> void:
    ready_count = ready_count + 1

func get_ready_count_value() -> int:
    return ready_count

class_name PhysicsProcessVirtualRuntimeSmoke
extends Node

# The validation script reads these counters after a few engine-driven physics frames.
var physics_process_count: int = 0
var last_physics_delta: float = 0.0

func _physics_process(delta: float) -> void:
    physics_process_count = physics_process_count + 1
    last_physics_delta = delta

func get_physics_process_count_value() -> int:
    return physics_process_count

func get_last_physics_delta_value() -> float:
    return last_physics_delta

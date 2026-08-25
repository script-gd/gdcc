class_name AwaitEngineSignal
extends Node

var phase: int = 0

func wait_process_frame() -> void:
    phase = 1
    await get_tree().process_frame
    phase = 2

func start_wait() -> void:
    wait_process_frame()

func read_phase() -> int:
    return phase

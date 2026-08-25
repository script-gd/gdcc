class_name AwaitSignalBasic
extends Node

signal resumed(value: int)

var phase: int = 0
var result: int = -1

func wait_once() -> void:
    phase = 1
    result = await resumed
    phase = 2

func start_wait() -> void:
    wait_once()

func emit_value(value: int) -> void:
    resumed.emit(value)

func read_phase() -> int:
    return phase

func read_result() -> int:
    return result

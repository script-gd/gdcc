class_name AwaitDynamicSignal
extends Node

signal dynamic_tick(value: int)

var result: int = -1
var done: bool = false

func await_dynamic(value: Variant) -> int:
    return int(await value)

func outer(value: Variant) -> void:
    result = await await_dynamic(value)
    done = true

func start_run(value: Variant) -> void:
    outer(value)

func emit_tick(value: int) -> void:
    dynamic_tick.emit(value)

func read_result() -> int:
    return result

func read_done() -> bool:
    return done

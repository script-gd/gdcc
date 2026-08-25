class_name AwaitDynamicLate
extends Node

signal release(value: int)

var result: int = -1
var done: bool = false

func produce() -> Variant:
    var value: int = await release
    return value + 2

func consume_completed(state: Variant) -> void:
    result = int(await state)
    done = true

func start_consume(state: Variant) -> void:
    consume_completed(state)

func emit_release(value: int) -> void:
    release.emit(value)

func read_result() -> int:
    return result

func read_done() -> bool:
    return done

class_name AwaitInteropInterpreted
extends Node

var result: int = -1
var done: bool = false

func consume_external(state: Variant) -> void:
    result = int(await state)
    done = true

func start_consume(state: Variant) -> void:
    consume_external(state)

func read_result() -> int:
    return result

func read_done() -> bool:
    return done

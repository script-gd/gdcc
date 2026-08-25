class_name LambdaAwaitSuspendPath
extends Node

signal tick(value: int)

var result: int = -1

func make_cb() -> Callable:
    return func() -> int:
        var value: int = await tick
        result = value
        return value

func emit_value(value: int) -> void:
    tick.emit(value)

class_name LambdaAwaitDoneFastPath
extends Node

signal tick(value: int)

var result: int = -1

func make_cb() -> Callable:
    var seed := 5
    return func(x: int) -> int:
        if x > 0:
            return seed + x
        var value: int = await tick
        result = value
        return value

func emit_value(value: int) -> void:
    tick.emit(value)

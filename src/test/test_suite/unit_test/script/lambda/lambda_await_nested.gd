class_name LambdaAwaitNested
extends Node

signal tick(value: int)

var order: int = 0

func make_outer() -> Callable:
    return func() -> int:
        order = order * 10 + 1
        var inner_cb: Callable = func() -> int:
            var inner_value: int = await tick
            order = order * 10 + 3
            return inner_value
        inner_cb.call()
        order = order * 10 + 2
        var outer_value: int = await tick
        order = order * 10 + 4
        return outer_value

func emit_value(value: int) -> void:
    tick.emit(value)

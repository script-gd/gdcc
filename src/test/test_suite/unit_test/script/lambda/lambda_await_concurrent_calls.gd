class_name LambdaAwaitConcurrentCalls
extends Node

signal tick(value: int)

var order: int = 0
var acc1: int = -1
var acc2: int = -1
var val1: int = -1
var val2: int = -1

func make_cb() -> Callable:
    var base := 10
    return func(id: int) -> int:
        var acc := base + id
        order = order * 10 + id
        var value: int = await tick
        if id == 1:
            acc1 = acc
            val1 = value
        else:
            acc2 = acc
            val2 = value
        order = order * 10 + id + 2
        return acc

func emit_value(value: int) -> void:
    tick.emit(value)

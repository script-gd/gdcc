class_name LambdaAwaitFireAndForgetInner
extends Node

signal tick

var order: int = 0

func inner() -> void:
    order = order * 10 + 2
    var ignored = await tick
    order = order * 10 + 5

func make_cb() -> Callable:
    return func() -> void:
        order = order * 10 + 1
        inner()
        order = order * 10 + 3
        var ignored = await tick
        order = order * 10 + 6

func emit_tick() -> void:
    tick.emit()

class_name LambdaAwaitSelfCapture
extends Node

signal tick

var flag: int = 0

func make_self_cb() -> Callable:
    return func() -> int:
        var ignored = await tick
        flag = 7
        return flag

func emit_tick() -> void:
    tick.emit()

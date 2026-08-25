class_name LambdaAwaitCaptureWrite
extends Node

signal tick

var first_seen: int = -1
var second_seen: int = -1
var done_count: int = 0
var calls: int = 0

func make_cb() -> Callable:
    var seed := 1
    return func() -> int:
        calls += 1
        if calls == 1:
            first_seen = seed
        else:
            second_seen = seed
        seed = seed + 100
        await tick
        done_count += 1
        return seed

func emit_tick() -> void:
    tick.emit()

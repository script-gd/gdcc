class_name LambdaAwaitNamedCoroutineChain
extends Node

signal tick(value: int)

var result: int = -1
var suspended: bool = false

func leaf() -> int:
    var value: int = await tick
    return value + 1

func step() -> int:
    var value: int = await leaf()
    return value * 2

func make_cb() -> Callable:
    var base := 100
    return func() -> int:
        suspended = true
        var value: int = await step()
        suspended = false
        result = base + value
        return result

func emit_value(value: int) -> void:
    tick.emit(value)

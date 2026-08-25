class_name LambdaAwaitAwaitedByNamed
extends Node

signal tick(value: int)

var order: int = 0
var result: int = -1

func make_cb() -> Callable:
    return func() -> int:
        order = order * 10 + 2
        var value: int = await tick
        order = order * 10 + 4
        return value * 2

func run() -> void:
    order = order * 10 + 1
    var cb: Callable = make_cb()
    var awaited = await cb.call()
    order = order * 10 + 5
    result = int(awaited)

func start_run() -> void:
    run()

func emit_value(value: int) -> void:
    tick.emit(value)

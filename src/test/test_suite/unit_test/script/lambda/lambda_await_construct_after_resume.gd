class_name LambdaAwaitConstructAfterResume
extends Node

signal tick(value: int)

var result: int = -1
var done: bool = false

func run() -> void:
    var base: int = await tick
    var cb: Callable = func() -> int:
        var value: int = await tick
        return base * 100 + value
    var awaited = await cb.call()
    result = int(awaited)
    done = true

func start_run() -> void:
    run()

func emit_value(value: int) -> void:
    tick.emit(value)

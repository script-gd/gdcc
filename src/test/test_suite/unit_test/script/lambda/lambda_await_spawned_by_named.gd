class_name LambdaAwaitSpawnedByNamed
extends Node

signal tick

var order: int = 0

func make_cb() -> Callable:
    return func() -> void:
        order = order * 10 + 2
        var ignored = await tick
        order = order * 10 + 4

func run() -> void:
    order = order * 10 + 1
    var cb: Callable = make_cb()
    cb.call()
    order = order * 10 + 3
    var ignored = await tick
    order = order * 10 + 5

func start_run() -> void:
    run()

func emit_tick() -> void:
    tick.emit()

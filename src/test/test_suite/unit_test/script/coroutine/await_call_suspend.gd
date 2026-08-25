class_name AwaitCallSuspend
extends Node

signal release(value: int)

var events: Array = []
var result: int = -1
var done: bool = false

func leaf() -> int:
    events.append("leaf:wait")
    var value: int = await release
    events.append("leaf:done")
    return value + 1

func middle() -> int:
    events.append("middle:wait")
    var value: int = await leaf()
    events.append("middle:done")
    return value + 2

func outer() -> void:
    events.append("outer:wait")
    result = await middle()
    events.append("outer:done")
    done = true

func start_run() -> void:
    outer()

func emit_release(value: int) -> void:
    release.emit(value)

func read_events() -> Array:
    return events

func read_result() -> int:
    return result

func read_done() -> bool:
    return done

class_name AwaitFireAndForget
extends Node

signal release(value: int)

var result: int = -1
var done: bool = false

func worker() -> void:
    result = await release
    done = true

func start_detached() -> void:
    worker()

func emit_release(value: int) -> void:
    release.emit(value)

func read_result() -> int:
    return result

func read_done() -> bool:
    return done

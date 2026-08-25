class_name AwaitTypedEngineBoundary
extends Node

signal release

var completed: bool = false

func typed_suspend() -> int:
    await release
    completed = true
    return 9

func emit_release() -> void:
    release.emit()

func read_completed() -> bool:
    return completed

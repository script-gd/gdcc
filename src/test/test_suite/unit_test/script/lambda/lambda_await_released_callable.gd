class_name LambdaAwaitReleasedCallable
extends Node

signal release_now

var result: int = -1

func make_cb() -> Callable:
    var seed := 41
    return func() -> void:
        var ignored = await release_now
        result = seed

func emit_release() -> void:
    release_now.emit()

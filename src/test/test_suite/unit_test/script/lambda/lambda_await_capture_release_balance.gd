class_name LambdaAwaitCaptureReleaseBalance
extends Node

signal tick

var text_out: String = ""
var size_out: int = -1

func make_cb() -> Callable:
    var text := "hello"
    var values: Array = [1, 2, 3]
    return func() -> String:
        var ignored = await tick
        text_out = text
        size_out = values.size() + int(values[0])
        return text

func emit_tick() -> void:
    tick.emit()

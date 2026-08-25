class_name LambdaAwaitCapture
extends Node

signal tick(value: int)

var label_prefix: String = ""
var label_seed: int = -1
var label_value: int = -1

func make_cb(seed: int, prefix: String) -> Callable:
    return func() -> int:
        var got: int = await tick
        label_prefix = prefix
        label_seed = seed
        label_value = got
        return got

func emit_value(value: int) -> void:
    tick.emit(value)

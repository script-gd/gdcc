class_name StaticAwaitLambdaInterop
extends Node

signal release(value: int)

var result: int = -1
var suspended: bool = false

static func static_leaf(peer: StaticAwaitLambdaInterop) -> int:
    var value: int = await peer.release
    return value + 1

func make_cb() -> Callable:
    return func() -> int:
        suspended = true
        var value: int = await StaticAwaitLambdaInterop.static_leaf(self)
        suspended = false
        result = value * 10
        return result

func emit_release(value: int) -> void:
    release.emit(value)

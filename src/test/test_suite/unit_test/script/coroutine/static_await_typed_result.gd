class_name StaticAwaitTypedResult
extends Node

signal release(value: int)

var result: int = -1
var done: bool = false

static func static_leaf(peer: StaticAwaitTypedResult) -> int:
    var value: int = await peer.release
    return value + 1

func run() -> void:
    result = await StaticAwaitTypedResult.static_leaf(self)
    done = true

func emit_release(value: int) -> void:
    release.emit(value)

func read_result() -> int:
    return result

func read_done() -> bool:
    return done

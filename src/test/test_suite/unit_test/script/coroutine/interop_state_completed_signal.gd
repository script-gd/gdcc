class_name InteropStateCompletedSignal
extends Node

signal release(value: int)

func produce() -> Variant:
    var value: int = await release
    return value * 2

func emit_release(value: int) -> void:
    release.emit(value)

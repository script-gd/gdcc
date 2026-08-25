class_name LambdaAwaitSignalConnectCallback
extends Node

signal pinged
signal stepped(value: int)

var result: int = -1

func setup() -> void:
    pinged.connect(func() -> void:
        var value: int = await stepped
        result = value
    )

func emit_ping() -> void:
    pinged.emit()

func emit_step(value: int) -> void:
    stepped.emit(value)

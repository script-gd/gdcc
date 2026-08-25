class_name AwaitSignalNested
extends Node

signal nested_value(value: int)

var result: int = -1
var done: bool = false

func signal_value() -> Signal:
    return nested_value

func await_returned_signal() -> Variant:
    return await signal_value()

func outer() -> void:
    result = int(await await_returned_signal())
    done = true

func start_run() -> void:
    outer()

func emit_value(value: int) -> void:
    nested_value.emit(value)

func read_result() -> int:
    return result

func read_done() -> bool:
    return done

class_name AwaitSignalArgs
extends Node

signal zero
signal one(value: int)
signal many(value: int, label: String)

var zero_done: bool = false
var zero_result: Variant = 99
var one_result: int = -1
var many_result: Array = []

func wait_zero() -> void:
    zero_result = await zero
    zero_done = true

func wait_one() -> void:
    one_result = await one

func wait_many() -> void:
    many_result = await many

func start_zero() -> void:
    wait_zero()

func start_one() -> void:
    wait_one()

func start_many() -> void:
    wait_many()

func emit_zero() -> void:
    zero.emit()

func emit_one(value: int) -> void:
    one.emit(value)

func emit_many(value: int, label: String) -> void:
    many.emit(value, label)

func read_zero_done() -> bool:
    return zero_done

func read_zero_result() -> Variant:
    return zero_result

func read_one_result() -> int:
    return one_result

func read_many_result() -> Array:
    return many_result

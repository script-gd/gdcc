class_name AwaitSignalConnectFailure
extends Node

var done: bool = false
var result: Variant = 99

func wait_invalid(value: Variant) -> void:
    result = await value
    done = true

func start_wait(value: Variant) -> void:
    wait_invalid(value)

func read_done() -> bool:
    return done

func read_result() -> Variant:
    return result

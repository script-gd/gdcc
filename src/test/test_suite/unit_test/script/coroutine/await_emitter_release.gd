class_name AwaitEmitterRelease
extends Node

var resumed: bool = false

func wait_external(value: Variant) -> void:
    await value
    resumed = true

func start_wait(value: Variant) -> void:
    wait_external(value)

func read_resumed() -> bool:
    return resumed

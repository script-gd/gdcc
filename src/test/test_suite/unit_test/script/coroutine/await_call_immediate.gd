class_name AwaitCallImmediate
extends Node

signal optional_wait

var done: bool = false
var result: int = -1

func maybe_wait(should_wait: bool) -> int:
    if should_wait:
        await optional_wait
    return 41

func run_immediate() -> void:
    result = await maybe_wait(false)
    done = true

func start_run() -> void:
    run_immediate()

func read_done() -> bool:
    return done

func read_result() -> int:
    return result

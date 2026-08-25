class_name AwaitRecursive
extends Node

signal base_value(value: int)

var result: int = -1
var done: bool = false

func recurse(depth: int) -> int:
    if depth == 0:
        return await base_value
    var child: int = await recurse(depth - 1)
    return child + depth

func run_recursive(depth: int) -> void:
    result = await recurse(depth)
    done = true

func start_run(depth: int) -> void:
    run_recursive(depth)

func emit_base(value: int) -> void:
    base_value.emit(value)

func read_result() -> int:
    return result

func read_done() -> bool:
    return done

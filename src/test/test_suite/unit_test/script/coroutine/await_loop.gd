class_name AwaitLoop
extends Node

signal tick(value: int)

var iteration: int = 0
var total: int = 0
var done: bool = false

func run_loop() -> void:
    var values: Array = [1, 2, 3]
    var labels: Array = ["a", "b", "c"]
    var totals: Dictionary = {"base": 5}
    var seen: Dictionary = {}
    for index in range(3):
        var emitted: int = await tick
        total += emitted + int(values[index])
        seen[labels[index]] = emitted
        iteration = index + 1
    total += int(totals["base"]) + int(seen["a"]) + int(seen["c"])
    done = true

func start_run() -> void:
    run_loop()

func emit_tick(value: int) -> void:
    tick.emit(value)

func read_iteration() -> int:
    return iteration

func read_total() -> int:
    return total

func read_done() -> bool:
    return done

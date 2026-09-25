class_name PackedRefFullUsage
extends Node

## Full-usage combination anchor for Packed*Array reference semantics: function calls /
## while / for live iteration / if / match / ternary / instance fields / lambda capture /
## coroutine await / signals share one packed identity along a single chain, and every
## construct's mutations must be visible to each other by reference semantics.

signal grown(value: PackedInt32Array, tag: int)
signal resume_now

var field: PackedInt32Array = PackedInt32Array([1])
var signal_hits: int = 0
var coro_done: bool = false

func _bump(a: PackedInt32Array, v: int) -> void:
    a.push_back(v)

func _classify(n: int) -> int:
    match n:
        0:
            return 0
        1:
            return 10
        _:
            return 99

func _on_grown(value: PackedInt32Array, tag: int) -> void:
    signal_hits += 1
    value.push_back(tag)

## Function calls + while + ternary + for live iteration + match + if: field [1] -> [1,2,7], returns 1133.
func run_control_flow() -> int:
    _bump(field, 2)
    var sum := 0
    var i := 0
    while i < field.size():
        var v: int = field[i]
        sum += v if v >= 0 else -v
        i += 1
    var visits := 0
    for v in field:
        visits += 1
        if visits == 1:
            _bump(field, 7)
    sum += _classify(1)
    if visits == 3 and field.size() == 3:
        sum += 100
    elif visits == 3:
        sum += 200
    else:
        sum += 300
    return sum * 10 + visits

## Lambda capture + signal argument: captured [5] -> lambda pushes 6 -> signal callback pushes 8, returns 381.
func run_signal_lambda() -> int:
    var captured := PackedInt32Array([5])
    var cb := func() -> void: captured.push_back(6)
    grown.connect(_on_grown)
    cb.call()
    grown.emit(captured, 8)
    grown.disconnect(_on_grown)
    return captured.size() * 100 + captured[2] * 10 + signal_hits

## Coroutine: pushes 3 before suspending (field sharing is immediately visible), then after
## resume_now pushes 4 and sets the completion flag.
func start_coroutine() -> void:
    _coro_body()

func _coro_body() -> void:
    _bump(field, 3)
    await resume_now
    _bump(field, 4)
    coro_done = true

func emit_resume() -> void:
    resume_now.emit()

func read_field_size() -> int:
    return field.size()

func read_coro_done() -> bool:
    return coro_done

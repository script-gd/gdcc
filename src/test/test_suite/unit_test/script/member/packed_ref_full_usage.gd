class_name PackedRefFullUsage
extends Node

## Packed*Array 引用语义的全用法组合锚点（packed_array_reference_semantics_plan.md
## Phase F 验收）：函数调用 / while / for 活迭代 / if / match / 三元 / 实例字段 /
## lambda 捕获 / 协程 await / 信号在同一条链路中共用 packed 共享身份，每个构造的
## mutation 都必须按引用语义互相可见。

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

## 函数调用 + while + 三元 + for 活迭代 + match + if：field [1] -> [1,2,7]，返回 1133。
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

## lambda 捕获 + 信号参数：captured [5] -> lambda push 6 -> 信号回调 push 8，返回 381。
func run_signal_lambda() -> int:
    var captured := PackedInt32Array([5])
    var cb := func() -> void: captured.push_back(6)
    grown.connect(_on_grown)
    cb.call()
    grown.emit(captured, 8)
    grown.disconnect(_on_grown)
    return captured.size() * 100 + captured[2] * 10 + signal_hits

## 协程：挂起前 push 3（字段共享立即可见），resume_now 后 push 4 并置完成位。
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

class_name OnreadyCoroutineEntryInit
extends Node

# Coroutine `_ready` thunk-entry anchor: both the direct internal call and the
# `super._ready()` path must apply the instance chain once before the first
# `await` suspends (the thunk entry initialization, not the sync shell, is the
# shared entry for all non-engine coroutine paths).
class Log extends RefCounted:
    static var entries: Array = []

    static func stamp(tag: String, value: int) -> int:
        entries.append(tag)
        return value

class CoroParent extends Node:
    signal proceed

    @onready var p_value: int = Log.stamp("coro_parent", 7)
    var suspended: int = 0
    var seen_value: int = -1
    var completed: int = 0

    func _ready() -> void:
        suspended += 1
        seen_value = p_value
        await proceed
        completed += 1

    func manual() -> void:
        _ready()

class CoroChild extends CoroParent:
    @onready var c_value: int = Log.stamp("coro_child", 9)
    var child_seen: int = -1
    var child_completed: int = 0

    func _ready() -> void:
        child_seen = c_value
        await super._ready()
        child_completed += 1

    func call_super_manual() -> void:
        await super._ready()

var child: CoroChild

func create_child() -> void:
    child = CoroChild.new()

func child_call_manual() -> void:
    child.manual()

func child_call_super_manual() -> void:
    child.call_super_manual()

func emit_proceed() -> void:
    child.proceed.emit()

func attach_child() -> void:
    add_child(child)

func get_log() -> Array:
    return Log.entries

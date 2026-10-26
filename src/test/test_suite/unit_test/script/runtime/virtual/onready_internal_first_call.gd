class_name OnreadyInternalFirstCall
extends Node

# Non-engine `_ready` entry anchor: the first qualifying manual call applies the
# instance's actual chain once and latches DONE; a later engine READY dispatch
# replays through the composite impl regardless of DONE (mirroring how Godot's
# `callp` runs `@implicit_ready` on every dispatched `_ready`).
class Probe extends RefCounted:
    static var applies: int = 0

    static func bump() -> int:
        applies += 1
        return 41

class Child extends Node:
    @onready var value: int = Probe.bump()
    var ready_calls: int = 0

    func _ready() -> void:
        ready_calls += 1

    func manual() -> void:
        _ready()

var child: Child

func create_child() -> void:
    child = Child.new()

func child_call_manual() -> void:
    child.manual()

func attach_child() -> void:
    add_child(child)

func get_apply_count() -> int:
    return Probe.applies

func get_value() -> int:
    return child.value

func get_ready_calls() -> int:
    return child.ready_calls

class_name OnreadyInheritedReadyDerivedOnready
extends Node

# Instance-type dispatch anchor: `_ready` is declared only on the base class while
# the onready chain lives on the subclass. A manual `_ready` enters the inherited
# base function whose lazy initialization must still resolve and apply the SUB
# chain through the per-root class lookup table (not the declaring class's chain).
class Log extends RefCounted:
    static var entries: Array = []

    static func stamp(tag: String, value: int) -> int:
        entries.append(tag)
        return value

class Base extends Node:
    var ready_calls: int = 0

    func _ready() -> void:
        ready_calls += 1

    func manual() -> void:
        _ready()

class Sub extends Base:
    @onready var value: int = Log.stamp("sub", 33)

var child: Sub

func create_child() -> void:
    child = Sub.new()

func child_call_manual() -> void:
    child.manual()

func attach_child() -> void:
    add_child(child)

func get_log() -> Array:
    return Log.entries

func get_value() -> int:
    return child.value

func get_ready_calls() -> int:
    return child.ready_calls

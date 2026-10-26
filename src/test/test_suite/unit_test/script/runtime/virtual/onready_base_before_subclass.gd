class_name OnreadyBaseBeforeSubclass
extends Node

# Mirror of the upstream Godot anchor `onready_base_before_subclass`: base onready
# applies before the subclass onready and both run before the most-derived user
# `_ready`. Property initializers cannot touch instance members, so a shared static
# log on the helper class records each initializer call in execution order.
class OrderLog extends RefCounted:
    static var entries: Array = []

    static func stamp(tag: String, value: int) -> int:
        entries.append(tag)
        return value

class Base extends Node:
    @onready var a: int = OrderLog.stamp("base", 11)

class Sub extends Base:
    @onready var b: int = OrderLog.stamp("sub", 22)

    func _ready() -> void:
        OrderLog.entries.append("sub_ready")

func run_case() -> void:
    var node = Sub.new()
    add_child(node)

func get_log() -> Array:
    return OrderLog.entries

class_name OnreadyExportOverride
extends Node

# `@export` writes before ready and the ready-time onready initializer wins: the
# externally stored value is overwritten by the chain apply on engine READY
# (matching Godot's export-then-onready ordering; the optional ONREADY_WITH_EXPORT
# diagnostic is intentionally not part of this anchor).
class Child extends Node:
    @export
    @onready var value: int = 5

var child: Child

func create_child() -> void:
    child = Child.new()

func store_value(v: int) -> void:
    child.value = v

func attach_child() -> void:
    add_child(child)

func get_value() -> int:
    return child.value

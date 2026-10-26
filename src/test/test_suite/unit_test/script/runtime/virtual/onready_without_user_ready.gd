class_name OnreadyWithoutUserReady
extends Node

# The engine-ready path must apply the onready chain even when no class on the
# chain declares a user `_ready` (Godot runs `@implicit_ready` regardless).
class NoReady extends Node:
    @onready var value: int = 17

func probe() -> int:
    var node = NoReady.new()
    add_child(node)
    return node.value

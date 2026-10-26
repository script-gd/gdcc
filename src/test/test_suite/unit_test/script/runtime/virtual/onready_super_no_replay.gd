class_name OnreadySuperNoReplay
extends Node

# `super._ready()` must not replay the parent onready chain: the composite impl
# already latched the shared DONE flag before forwarding to the child `_ready`,
# so the super entry's lazy initialization is a no-op and the initializer runs
# exactly once across both entry paths.
class Probe extends RefCounted:
    static var applies: int = 0

    static func bump() -> int:
        applies += 1
        return 7

class Parent extends Node:
    @onready var value: int = Probe.bump()
    var ready_calls: int = 0

    func _ready() -> void:
        ready_calls += 1

class Child extends Parent:
    var child_ready_calls: int = 0

    func _ready() -> void:
        child_ready_calls += 1
        super._ready()

var child: Child

func run_case() -> void:
    child = Child.new()
    add_child(child)

func get_apply_count() -> int:
    return Probe.applies

func get_value() -> int:
    return child.value

func get_parent_ready_calls() -> int:
    return child.ready_calls

func get_child_ready_calls() -> int:
    return child.child_ready_calls

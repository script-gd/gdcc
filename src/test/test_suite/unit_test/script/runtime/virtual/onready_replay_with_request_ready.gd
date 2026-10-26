class_name OnreadyReplayWithRequestReady
extends Node

# Re-ready semantics: re-entering the tree without `request_ready()` must not
# replay the chain, while an armed `request_ready()` replay overwrites the field
# and releases the previous RefCounted value (observed via instance-id validity,
# not internals).
class Probe extends RefCounted:
    static var applies: int = 0

    static func bump() -> int:
        applies += 1
        return 33

class Child extends Node:
    @onready var res: RefCounted = RefCounted.new()
    @onready var value: int = Probe.bump()

var child: Child

func run_case() -> void:
    child = Child.new()
    add_child(child)

func remove_child_node() -> void:
    remove_child(child)

func reattach_child() -> void:
    add_child(child)

func arm_request_ready() -> void:
    child.request_ready()

func get_apply_count() -> int:
    return Probe.applies

func get_value() -> int:
    return child.value

func get_res_instance_id() -> int:
    return child.res.get_instance_id()

func overwrite_value(v: int) -> void:
    child.value = v

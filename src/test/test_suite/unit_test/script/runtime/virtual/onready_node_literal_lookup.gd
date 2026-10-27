class_name OnreadyNodeLiteralLookup
extends Node

# The outer class is a mountable harness only: the runner attaches it as the
# target node, so every lookup-dependent @onready field lives on inner classes
# created through factories. Validation must not reference `Outer.Inner` names.
class Probe extends Node:
    @onready var child_node: Node = $Child
    @onready var nested_leaf: Node = $Branch/Leaf
    @onready var quoted_node: Node = $"Name With Space"
    @onready var unique_node: Node = %Unique
    @onready var spaced_unique_node: Node = %"Unique Name"
    @onready var untyped_child = $Child

    @onready var child_name: StringName = $Child.name
    @onready var unique_name: StringName = %Unique.get_name()
    @onready var get_name_callable: Callable = $Child.get_name
    @onready var renamed_signal: Signal = $Child.renamed

    @onready var self_name: StringName = $".".get_name()
    @onready var parent_name: StringName = $Child.get_parent().get_name()

    var ready_child: Node
    var ready_name: StringName

    func _ready() -> void:
        ready_child = child_node
        ready_name = child_name

class Plain extends Node:
    @onready var child_node: Node = $Child

# Stamps counts chain applications through an explicit static receiver, so its
# counter proves both the parent-before-child order and the absence of replay.
class Stamps extends RefCounted:
    static var applies: int = 0

    static func bump() -> int:
        applies += 1
        return applies

class Base extends Node:
    @onready var base_stamp: int = Stamps.bump()
    @onready var base_child: Node = $Child

    var base_ready_calls: int = 0

    func _ready() -> void:
        base_ready_calls += 1

class Sub extends Base:
    @onready var sub_stamp: int = Stamps.bump()
    @onready var sub_leaf: Node = $Child/Leaf

    var observed_base: Node
    var observed_leaf: Node
    var sub_ready_calls: int = 0

    func _ready() -> void:
        sub_ready_calls += 1
        observed_base = base_child
        observed_leaf = sub_leaf
        super._ready()

func create_probe() -> Probe:
    return Probe.new()

func create_plain() -> Plain:
    return Plain.new()

func create_sub() -> Sub:
    return Sub.new()

func get_stamp_applies() -> int:
    return Stamps.applies

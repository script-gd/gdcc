class_name PropertyInitializerObjectAndScalarSmoke
extends Node

var ready_value: int = (1 + 2) * 5
var ready_axis: Vector3 = Vector3(1.0, 2.0, 3.0)
var ready_node: Node = Node.new()
var ready_object: Object = Node.new()
var ready_ref: RefCounted = RefCounted.new()

func read_value() -> int:
    return ready_value

func read_axis_y() -> float:
    return ready_axis.y

func read_node_class() -> String:
    return ready_node.get_class()

func read_object_class() -> String:
    return ready_object.get_class()

func read_ref_count() -> int:
    return ready_ref.get_reference_count()

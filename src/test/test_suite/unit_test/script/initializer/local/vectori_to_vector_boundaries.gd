class_name LocalInitializerVectorIToVectorBoundaries
extends Node

var assigned: Vector3 = Vector3(0.0, 0.0, 0.0)

func take_vector(value: Vector3) -> float:
    return value.x + value.y + value.z

func return_vector(value: Vector3i) -> Vector3:
    return value

func local_sum() -> float:
    var value: Vector3 = Vector3i(1, 2, 3)
    return value.x + value.y + value.z

func assignment_sum(value: Vector3i) -> float:
    assigned = value
    return assigned.x + assigned.y + assigned.z

func fixed_arg_sum(value: Vector3i) -> float:
    return take_vector(value)

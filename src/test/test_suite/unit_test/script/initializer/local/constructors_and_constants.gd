class_name LocalInitializerConstructorsAndConstants
extends Node

func constructor_matches() -> bool:
    var point: Vector3i = Vector3i(1, 2, 3)
    return point == Vector3i(1, 2, 3)

func read_type_meta_values() -> bool:
    var axis: Vector3 = Vector3.ZERO
    var tone: Color = Color.RED
    return axis == Vector3.ZERO and tone == Color.RED

func empty_array_size() -> int:
    var values: Array = Array()
    return values.size()

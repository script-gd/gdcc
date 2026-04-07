class_name BuiltinPropertyAccessSmoke
extends Node

func vector_y() -> float:
    var vector: Vector3 = Vector3(1.0, 2.0, 3.0)
    return vector.y

func color_r() -> float:
    var color: Color = Color(0.25, 0.5, 0.75, 1.0)
    return color.r

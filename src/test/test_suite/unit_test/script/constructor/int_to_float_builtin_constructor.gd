class_name BuiltinConstructorIntToFloatBoundary
extends Node

func vector2_mixed_sum() -> float:
    var vector: Vector2 = Vector2(12, -3.5)
    return vector.x + vector.y

func vector3_mixed_sum() -> float:
    var vector: Vector3 = Vector3(-7, 2.25, 11)
    return vector.x + vector.y + vector.z

func vector4_mixed_sum() -> float:
    var vector: Vector4 = Vector4(5, -1.5, 8, 0.25)
    return vector.x + vector.y + vector.z + vector.w

func color_mixed_sum() -> float:
    var color: Color = Color(0.125, 1, 2, 0.5)
    return color.r + color.g + color.b + color.a

func rect2_mixed_size_area() -> float:
    var rect: Rect2 = Rect2(1, -2.5, 3, 4.5)
    return rect.size.x * rect.size.y

func plane_mixed_sum() -> float:
    var plane: Plane = Plane(1, -2.5, 3, 4.25)
    return plane.x + plane.y + plane.z + plane.d

func quaternion_mixed_sum() -> float:
    var rotation: Quaternion = Quaternion(0.5, 1, -2, 3.25)
    return rotation.x + rotation.y + rotation.z + rotation.w

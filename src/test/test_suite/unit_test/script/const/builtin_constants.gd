class_name ConstBuiltinConstants
extends Node

# Frozen builtin payloads: non-symmetric Basis logical columns distinguish the
# canonical column order from physical row storage at runtime.
const V = Vector2(1.5, -2.5)
const VI = Vector3i(1, -2, 3)
const VI4 = Vector4i(1, -2, 3, -4)
const B = Basis(Vector3(1, 2, 3), Vector3(4, 5, 6), Vector3(7, 8, 10))
const T = Transform3D(B, Vector3(9, 8, 7))
const TINT = Color(0.25, 0.5, 0.75, 0.5)
const UP = Vector3.UP
const DIAG_SQ = V.x * V.x + V.y * V.y
const T2 = Transform2D(Vector2(1, 2), Vector2(3, 4), Vector2(5, 6))
const P = Projection(
        Vector4(1, 2, 3, 4), Vector4(5, 6, 7, 8),
        Vector4(9, 10, 11, 12), Vector4(13, 14, 15, 17))

func get_v() -> Vector2:
    return V

func get_vi() -> Vector3i:
    return VI

func get_vi4() -> Vector4i:
    return VI4

func get_transform() -> Transform3D:
    return T

func get_tint() -> Color:
    return TINT

func get_up() -> Vector3:
    return UP

func diag_sq() -> float:
    return DIAG_SQ

func get_t2() -> Transform2D:
    return T2

func get_projection() -> Projection:
    return P

class_name InboundVectorIToVectorDynamicCall
extends Node

func take_vector2(value: Vector2) -> float:
    return value.x + value.y

func take_vector3(value: Vector3) -> float:
    return value.x + value.y + value.z

func take_vector4(value: Vector4) -> float:
    return value.x + value.y + value.z + value.w

func vector3_y(value: Vector3) -> float:
    return value.y

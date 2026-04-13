class_name BuiltinPropertyWritebackVector3Smoke
extends Node

func vector_x_after_write() -> float:
    var vector: Vector3 = Vector3(1.0, 2.0, 3.0)
    vector.x = vector.y + vector.z
    return vector.x

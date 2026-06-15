class_name BenchmarkVector3Transform
extends Node

var _seed: Vector3 = Vector3.ZERO

func prepare() -> void:
    _seed = Vector3(1.5, -2.0, 0.25)

func baseline() -> float:
    return _seed.x

func benchmark() -> float:
    var value: Vector3 = _seed
    var iteration := 0
    while iteration < 48:
        value = value + Vector3(0.5, 1.0, -0.25)
        value = value * 1.03125
        iteration = iteration + 1
    return value.x + value.y + value.z

func check(result: float) -> bool:
    return abs(result - 138.32829839159592) < 0.0001

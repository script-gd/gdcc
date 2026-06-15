class_name BenchmarkNewtonSqrtInterpreter
extends Node

var _target := 0.0

func prepare() -> void:
    _target = 19.0

func baseline() -> float:
    return _target

func benchmark() -> float:
    var estimate := 1.0
    var iteration := 0
    while iteration < 12:
        estimate = 0.5 * (estimate + _target / estimate)
        iteration = iteration + 1
    return estimate

func check(result: float) -> bool:
    return abs(result - 4.358898943540674) < 0.000001

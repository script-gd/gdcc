class_name BenchmarkIntLoopInterpreter
extends Node

var _seed := 0

func prepare() -> void:
    _seed = 1

func baseline() -> int:
    return _seed

func benchmark() -> int:
    var total := 0
    var value := 0
    while value < 128:
        total = total + value + _seed
        value = value + 1
    return total

func check(result: int) -> bool:
    return result == 8256

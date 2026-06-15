class_name BenchmarkArrayMutationInterpreter
extends Node

var values: Array = Array()

func prepare() -> void:
    _reset_values()

func baseline() -> int:
    return values.size()

func benchmark() -> int:
    var index := 0
    while index < values.size():
        values[index] = int(values[index]) + index + 1
        index = index + 1

    var total := 0
    index = 0
    while index < values.size():
        total = total + int(values[index])
        index = index + 1
    return total

func check(result: int) -> bool:
    var passed := true
    if result != 30:
        passed = false
    elif values.size() != 4:
        passed = false
    elif int(values[0]) != 3:
        passed = false
    elif int(values[1]) != 6:
        passed = false
    elif int(values[2]) != 9:
        passed = false
    elif int(values[3]) != 12:
        passed = false

    _reset_values()
    return passed

func _reset_values() -> void:
    values = Array()
    values.push_back(2)
    values.push_back(4)
    values.push_back(6)
    values.push_back(8)

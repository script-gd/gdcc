class_name BenchmarkDictionaryLookup
extends Node

var _keys: Array = Array()
var _values: Dictionary = Dictionary()

func prepare() -> void:
    _keys = Array()
    _values = Dictionary()

    _keys.push_back("alpha")
    _keys.push_back("beta")
    _keys.push_back("gamma")
    _keys.push_back("delta")

    _values["alpha"] = 11
    _values["beta"] = 17
    _values["gamma"] = 23
    _values["delta"] = 29

func baseline() -> int:
    return _keys.size()

func benchmark() -> int:
    var index := 0
    var total := 0
    while index < _keys.size():
        var key = _keys[index]
        total = total + int(_values[key])
        index = index + 1
    return total

func check(result: int) -> bool:
    return result == 80

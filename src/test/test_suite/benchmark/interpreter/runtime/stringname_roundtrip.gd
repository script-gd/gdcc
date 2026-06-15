class_name BenchmarkStringNameRoundtripInterpreter
extends Node

var _first_name: StringName = &""
var _second_name: StringName = &""
var _third_name: StringName = &""
var _fourth_name: StringName = &""

func prepare() -> void:
    _first_name = "Player"
    _second_name = "Enemy"
    _third_name = "Camera"
    _fourth_name = "World"

func baseline() -> int:
    return 4

func benchmark() -> int:
    var first_text: String = _first_name
    var second_text: String = _second_name
    var third_text: String = _third_name
    var fourth_text: String = _fourth_name
    var total := first_text.length()
    total = total + second_text.length()
    total = total + third_text.length()
    total = total + fourth_text.length()
    return total

func check(result: int) -> bool:
    if result != 22:
        return false
    var first_text: String = _first_name
    return first_text == "Player"

class_name BenchmarkNodeBatchOps
extends Node

var _expected_result: int = 38
var _name_property: StringName = &"name"
var _set_method: StringName = &"set"
var _get_method: StringName = &"get"
var _alpha_one: String = "AlphaOne"
var _beta_two: String = "BetaTwo"
var _gamma_three: String = "GammaThree"
var _delta_four: String = "DeltaFour"
var _alpha_one_x: String = "AlphaOneX"
var _beta_two_x: String = "BetaTwoX"
var _gamma_three_x: String = "GammaThreeX"
var _delta_four_x: String = "DeltaFourX"
var _set_args: Array = Array()
var _get_args: Array = Array()

func prepare() -> void:
    _set_args = Array()
    _set_args.push_back(_name_property)
    _set_args.push_back(_alpha_one)
    _get_args = Array()
    _get_args.push_back(_name_property)
    _clear_children()

func baseline() -> int:
    return get_child_count()

func benchmark() -> int:
    var first := Node.new()
    var second := Node.new()
    var third := Node.new()
    var fourth := Node.new()

    first.callv(_set_method, _set_args)
    _set_args[1] = _beta_two
    second.callv(_set_method, _set_args)
    _set_args[1] = _gamma_three
    third.callv(_set_method, _set_args)
    _set_args[1] = _delta_four
    fourth.callv(_set_method, _set_args)

    add_child(first)
    add_child(second)
    add_child(third)
    add_child(fourth)

    _set_args[1] = _alpha_one_x
    first.callv(_set_method, _set_args)
    _set_args[1] = _beta_two_x
    second.callv(_set_method, _set_args)
    _set_args[1] = _gamma_three_x
    third.callv(_set_method, _set_args)
    _set_args[1] = _delta_four_x
    fourth.callv(_set_method, _set_args)

    var total := 0
    var first_text: String = first.callv(_get_method, _get_args)
    var second_text: String = second.callv(_get_method, _get_args)
    var third_text: String = third.callv(_get_method, _get_args)
    var fourth_text: String = fourth.callv(_get_method, _get_args)
    total = total + first_text.length()
    total = total + second_text.length()
    total = total + third_text.length()
    total = total + fourth_text.length()

    remove_child(first)
    remove_child(second)
    remove_child(third)
    remove_child(fourth)

    first.free()
    second.free()
    third.free()
    fourth.free()

    return total

func check(result: int) -> bool:
    var passed := true
    if result != _expected_result:
        passed = false
    elif get_child_count() != 0:
        passed = false

    _clear_children()
    return passed

func _clear_children() -> void:
    while get_child_count() > 0:
        var child := get_child(0)
        remove_child(child)
        child.free()

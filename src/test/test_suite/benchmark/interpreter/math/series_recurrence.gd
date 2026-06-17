class_name BenchmarkSeriesRecurrenceInterpreter
extends Node

var _geometric_start := 0.0
var _ratio := 0.0
var _fib_steps := 0
var _alternating_steps := 0
var _alternating_seed := 0
var _alternating_step := 0

func prepare() -> void:
    _geometric_start = 1.0
    _ratio = 0.5
    _fib_steps = 12
    _alternating_steps = 11
    _alternating_seed = 3
    _alternating_step = 5

func baseline() -> float:
    return _geometric_start

func benchmark() -> float:
    var geometric_sum := 0.0
    var geometric_term := _geometric_start
    var geometric_index := 0
    while geometric_index < 10:
        geometric_sum = geometric_sum + geometric_term
        geometric_term = geometric_term * _ratio
        geometric_index = geometric_index + 1

    var fib_previous := 1.0
    var fib_current := 1.0
    var fib_index := 0
    while fib_index < _fib_steps:
        var fib_next := fib_previous + fib_current
        fib_previous = fib_current
        fib_current = fib_next
        fib_index = fib_index + 1

    var alternating_value := 3.0
    var alternating_direction := 1.0
    var alternating_index := 0
    while alternating_index < _alternating_steps:
        alternating_value = alternating_value + alternating_direction * 5.0
        alternating_direction = 0.0 - alternating_direction
        alternating_index = alternating_index + 1

    return geometric_sum + fib_current + alternating_value

func check(result: float) -> bool:
    return abs(result - 386.998046875) < 0.000001

class_name BenchmarkSlidingVariance
extends Node

var _window_size: int = 16
var _update_count: int = 64
var _count: int = 0
var _sum: int = 0
var _sum_squares: int = 0

func prepare() -> void:
    _count = _window_size
    _sum = 0
    _sum_squares = 0

    var index: int = 0
    while index < _window_size:
        var value: int = _sample_value(index)
        _sum += value
        _sum_squares += value * value
        index += 1

func baseline() -> int:
    return _scaled_variance()

func benchmark() -> int:
    var index: int = _window_size
    var update_index: int = 0
    while update_index < _update_count:
        var incoming: int = _sample_value(index)
        var outgoing: int = _sample_value(index - _window_size)
        _sum += incoming - outgoing
        _sum_squares += incoming * incoming - outgoing * outgoing
        index += 1
        update_index += 1
    return _scaled_variance()

func check(result: int) -> bool:
    return result == _expected_scaled_variance()

func _sample_value(index: int) -> int:
    var residue: int = ((index * index) + (3 * index) + 7) % 13
    return 32 + 4 * index + residue

func _scaled_variance() -> int:
    return _sum_squares * _count - _sum * _sum

func _expected_scaled_variance() -> int:
    var sum: int = 0
    var sum_squares: int = 0
    var index: int = 0
    while index < _window_size:
        var value: int = _sample_value(index)
        sum += value
        sum_squares += value * value
        index += 1

    var update_index: int = 0
    var next_index: int = _window_size
    while update_index < _update_count:
        var incoming: int = _sample_value(next_index)
        var outgoing: int = _sample_value(next_index - _window_size)
        sum += incoming - outgoing
        sum_squares += incoming * incoming - outgoing * outgoing
        next_index += 1
        update_index += 1

    return sum_squares * _window_size - sum * sum

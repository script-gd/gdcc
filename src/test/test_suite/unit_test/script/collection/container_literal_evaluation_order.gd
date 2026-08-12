class_name ContainerLiteralEvaluationOrder
extends Node

var side_effect_log: Array = []

func reset_log() -> void:
	side_effect_log = []

func mark(tag: int) -> int:
	side_effect_log.push_back(tag)
	return tag

func array_left_to_right() -> int:
	reset_log()
	var values = [mark(1), mark(2), mark(3)]
	return values.size() * 1000 + int(side_effect_log[0]) * 100 + int(side_effect_log[1]) * 10 + int(side_effect_log[2]) + int(values[2])

func dictionary_key_value_order() -> int:
	reset_log()
	var scores = {mark(1): mark(2), mark(3): mark(4)}
	return scores.size() * 10000 + int(side_effect_log[0]) * 1000 + int(side_effect_log[1]) * 100 + int(side_effect_log[2]) * 10 + int(side_effect_log[3]) + int(scores[1]) + int(scores[3])

func discarded_literal_keeps_side_effects() -> int:
	reset_log()
	[mark(5), mark(6)]
	{mark(7): mark(8)}
	return side_effect_log.size() * 1000 + int(side_effect_log[0]) * 100 + int(side_effect_log[1]) * 10 + int(side_effect_log[2]) + int(side_effect_log[3])

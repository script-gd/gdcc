class_name ForKnownIterableLoopSmoke
extends Node

func rebuild_string(text: String) -> String:
	var result := ""
	for character in text:
		result += character
	return result

func typed_array_sum(values: Array[int]) -> int:
	var total := 0
	for value in values:
		total += value
	return total

func typed_dictionary_key_length_sum(table: Dictionary[String, int]) -> int:
	var total := 0
	for key in table:
		total += key.length()
	return total

func packed_int_sum(values: PackedInt32Array) -> int:
	var total := 0
	for value in values:
		total += value
	return total

func packed_string_join(values: PackedStringArray) -> String:
	var result := ""
	for value in values:
		result += value
	return result

func float_shorthand_sum(limit: float) -> float:
	var total := 0.0
	for value in limit:
		total += value
	return total

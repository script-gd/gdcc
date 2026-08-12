class_name ArrayLiteralRoundtrip
extends Node

var typed_property: Array[int] = [10, 20, 30]

func generic_create_size_subscript_mutation() -> int:
	# Generic Array elements are Variant; use arithmetic / compound assign without int().
	var values = [2, 4, 6]
	values[1] = values[1] + 5
	var total := 0
	var index := 0
	while index < values.size():
		total += values[index]
		index += 1
	return values.size() * 1000 + total * 10 + values[1]

func empty_and_mixed_and_nested() -> int:
	var empty = []
	var mixed = [1, "x", true]
	var nested = [[1, 2], [3]]
	var row = nested[0]
	var nested_cell = row[1]
	var immediate = [10, 20][1]
	return empty.size() * 100000 + mixed.size() * 10000 + nested.size() * 1000 + nested_cell * 100 + immediate

func typed_local_assign_return_and_call() -> int:
	var local: Array[int] = [1, 2]
	local = [3, 4, 5]
	# Array[int] subscript is already int — no cast constructor.
	return local.size() * 100 + take_typed_array([7, 8]) * 10 + return_typed_array()[0]

func take_typed_array(values: Array[int]) -> int:
	return values.size() + values[0]

func return_typed_array() -> Array[int]:
	return [9]

func variant_pack_and_discard_side_effect() -> int:
	var boxed: Variant = [1, 2]
	var counter := 0
	counter = bump(counter)
	[bump(counter), bump(counter)]
	# Variant-keyed / indexed reads stay Variant until a typed boundary.
	var first: int = boxed[0]
	var second: int = boxed[1]
	return first * 100 + second * 10 + counter

func bump(value: int) -> int:
	return value + 1

func typed_property_seed() -> int:
	return typed_property.size() * 100 + typed_property[0] + typed_property[2]

class_name ContainerLiteralNestedUntyped
extends Node

# Nested Array-of-Array: intermediate rows stay Variant; no int() on reads.
func nested_array_of_arrays() -> int:
	var matrix = [[1, 2], [3, 4, 5], [6]]
	var row = matrix[1]
	var cell = row[2]
	var total := 0
	var r := 0
	while r < matrix.size():
		var cells = matrix[r]
		var c := 0
		while c < cells.size():
			total += cells[c]
			c += 1
		r += 1
	return matrix.size() * 1000 + row.size() * 100 + cell * 10 + total

# Nested Dictionary-of-Dictionary: chain through Variant locals without casts.
func nested_dict_of_dicts() -> int:
	var tree = {
		"left": {"value": 2, "leaf": 1},
		"right": {"value": 5}
	}
	var left = tree["left"]
	var value = left["value"]
	return tree.size() * 100 + left.size() * 10 + value

# Mutual nest: Array of Array + Dictionary; Dictionary holds Array.
func mutual_array_dict_nest() -> int:
	var grid = [[10, 20], {"k": 3, "arr": [7, 8]}]
	var first_row = grid[0]
	var bag = grid[1]
	var nested_arr = bag["arr"]
	var a0 = first_row[0]
	var b0 = nested_arr[0]
	return grid.size() * 1000 + first_row.size() * 100 + bag.size() * 10 + a0 + b0

# Deeper mutual nest: dict-of-array-of-dict + array-of-dict-of-array.
func deeper_mutual_nest() -> int:
	var catalog = {
		"rows": [
			{"id": 1, "cells": [4, 5]},
			{"id": 2, "cells": [6]}
		]
	}
	var rows = catalog["rows"]
	var first = rows[0]
	var cells = first["cells"]
	var second_id = rows[1]["id"]
	return catalog.size() * 10000 + rows.size() * 1000 + first.size() * 100 + cells[1] * 10 + second_id

# Generic Array create/mutate/sum without int()/float() constructors.
func generic_array_untyped_sum_mutate() -> int:
	var values = [2, 4, 6]
	values[1] = values[1] + 5
	var total := 0
	var index := 0
	while index < values.size():
		total += values[index]
		index += 1
	var first: int = values[0]
	return values.size() * 1000 + total * 10 + first + values[1]

# Generic Dictionary lookup/mutate without int() wrappers.
func generic_dict_untyped_lookup_mutate() -> int:
	var scores = {"alpha": 2, "beta": 5}
	scores["alpha"] = scores["alpha"] + 4
	return scores.size() * 100 + scores["alpha"] * 10 + scores["beta"]

# Nested generic -> typed int locals at the final boundary only.
func typed_boundary_from_nested_generic() -> int:
	var nested = [[9], {"n": 4}]
	var n: int = nested[0][0]
	var m: int = nested[1]["n"]
	return n * 10 + m

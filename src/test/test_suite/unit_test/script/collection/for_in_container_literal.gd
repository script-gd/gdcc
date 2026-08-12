class_name ForInContainerLiteral
extends Node

# Direct generic Array literal as iterable; iterator is Variant (no int()).
func for_in_array_literal_sum() -> int:
	var total := 0
	for value in [2, 4, 6, 8]:
		total += value
	return total

# Empty Array literal iterable contributes zero.
func for_in_empty_array_literal() -> int:
	var total := 1
	for _value in []:
		total += 1
	return total

# Typed Array[int] literal local then for-in (element already int).
func for_in_typed_array_literal() -> int:
	var values: Array[int] = [1, 3, 5]
	var total := 0
	for value in values:
		total += value
	return total * 10 + values.size()

# Nested Array-of-Array literal: outer + inner for-in without casts.
func for_in_nested_array_literal() -> int:
	var total := 0
	for row in [[1, 2], [3], [4, 5, 6]]:
		for cell in row:
			total += cell
	return total

# Dictionary literal keys via for-in; values read by subscript (no int()).
func for_in_dict_literal_keys_and_values() -> int:
	var scores = {"a": 2, "bb": 5, "ccc": 7}
	var key_len := 0
	var value_sum := 0
	for key in scores:
		key_len += key.length()
		value_sum += scores[key]
	return scores.size() * 1000 + key_len * 100 + value_sum

# Empty Dictionary literal iterable.
func for_in_empty_dict_literal() -> int:
	var count := 0
	for _key in {}:
		count += 1
	return count

# Nested dict-of-array: for keys, then for elements of each array value.
func for_in_dict_of_array_literal() -> int:
	var groups = {"x": [1, 2], "y": [3, 4, 5]}
	var total := 0
	for key in groups:
		var row = groups[key]
		for cell in row:
			total += cell
	return groups.size() * 100 + total

# Mutual nest built as literal, then for-in over Array holding Dictionaries.
func for_in_array_of_dict_literal() -> int:
	var rows = [{"n": 1}, {"n": 2}, {"n": 4}]
	var total := 0
	for row in rows:
		total += row["n"]
	return rows.size() * 100 + total

# Side-effecting literal elements evaluated once before iteration starts.
func for_in_literal_element_side_effects() -> int:
	var log: Array = []
	var total := 0
	for value in [mark(log, 1), mark(log, 2), mark(log, 3)]:
		total += value
	return log.size() * 1000 + log[0] * 100 + log[1] * 10 + log[2] + total

func mark(log: Array, tag: int) -> int:
	log.push_back(tag)
	return tag

# break/continue over Array literal iterable.
func for_in_array_literal_break_continue() -> int:
	var total := 0
	for value in [1, 2, 3, 4, 5, 6]:
		if value == 2:
			continue
		if value == 5:
			break
		total += value
	return total

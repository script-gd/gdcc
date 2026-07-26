class_name ForGenericVariantLoopSmoke
extends Node

func array_sum(values) -> int:
	var total := 0
	for item in values:
		total += item
	return total

func array_string_join(parts) -> String:
	var result := ""
	for part in parts:
		result += part
	return result

func dictionary_key_sum(table) -> int:
	var total := 0
	for key in table:
		total += key
	return total

func nested_loop_product(rows) -> int:
	var total := 0
	for row in rows:
		for col in row:
			total += col
	return total

func conditional_accumulate(values) -> int:
	var positive_sum := 0
	var negative_sum := 0
	for item in values:
		if item > 0:
			positive_sum += item
		else:
			negative_sum += item
	return positive_sum * 100 + negative_sum

func multi_pass_transform(values) -> int:
	var doubled_sum := 0
	for item in values:
		doubled_sum += item * 2
	var tripled_sum := 0
	for item in values:
		tripled_sum += item * 3
	return doubled_sum + tripled_sum

func dictionary_value_lookup(table, keys) -> int:
	var total := 0
	for key in keys:
		total += table[key]
	return total

func nested_with_conditional(matrix) -> int:
	var above_threshold := 0
	var below_threshold := 0
	for row in matrix:
		for cell in row:
			if cell >= 5:
				above_threshold += cell
			else:
				below_threshold += cell
	return above_threshold * 10 + below_threshold

func string_build_from_arrays(prefixes, suffixes) -> String:
	var result := ""
	for p in prefixes:
		for s in suffixes:
			result += p
			result += s
	return result

func weighted_sum(values, weights) -> int:
	var total := 0
	var i := 0
	for item in values:
		total += item * weights[i]
		i += 1
	return total

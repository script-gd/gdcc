class_name ForBreakContinueSmoke
extends Node

func for_break_before_sum() -> int:
	var total := 0
	for i in range(10):
		if i == 4:
			break
		total = total + i
	return total

func for_continue_skips_even() -> int:
	var total := 0
	for i in range(6):
		if i % 2 == 0:
			continue
		total = total + i
	return total

func for_break_and_continue_mix() -> int:
	var total := 0
	for i in range(10):
		if i == 7:
			break
		if i % 2 == 0:
			continue
		total = total + i
	return total

func nested_for_no_break_product() -> int:
	# Nested for without break/continue remains compile-ready and complements
	# unit tests that cover nested for loop-control edges.
	var total := 0
	for i in range(3):
		for j in range(2):
			total = total + 1
	return total

func nested_for_inner_break_reads_iterators() -> int:
	# Nested for body must keep refined int iterator types across suite overlays
	# so C codegen never sees bare int -> Variant assigns when reading i/j.
	var total := 0
	for i in range(3):
		for j in range(2):
			if j == 1:
				break
			total = total + j
		total = total + i
	return total

func nested_for_continue_reads_inner_iterator() -> int:
	var total := 0
	for i in range(3):
		for j in range(4):
			if j % 2 == 0:
				continue
			total = total + j
		total = total + i
	return total

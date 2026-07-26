class_name ForRangeLoopSmoke
extends Node

func range_stop_sum() -> int:
	var total := 0
	for i in range(5):
		total += i
	return total

func range_start_end_sum() -> int:
	var total := 0
	for i in range(2, 6):
		total += i
	return total

func range_step_sum() -> int:
	var total := 0
	for i in range(0, 10, 3):
		total += i
	return total

func range_negative_step_sum() -> int:
	var total := 0
	for i in range(8, 2, -2):
		total += i
	return total

func range_zero_step_empty() -> int:
	var count := 0
	for i in range(1, 5, 0):
		count += 1
	return count

func int_shorthand_sum() -> int:
	var total := 0
	for i in 4:
		total += i
	return total

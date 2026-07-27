class_name NestedForWhileBreakContinueSmoke
extends Node

func nested_while_inner_break() -> int:
	var total := 0
	var outer := 0
	while outer < 3:
		var inner := 0
		while inner < 5:
			if inner == 2:
				break
			total = total + inner
			inner = inner + 1
		outer = outer + 1
	return total

func for_over_while_inner_continue() -> int:
	var total := 0
	for outer in range(3):
		var inner := 0
		while inner < 4:
			inner = inner + 1
			if inner % 2 == 0:
				continue
			total = total + inner
	return total

func nested_while_outer_break() -> int:
	var total := 0
	var outer := 0
	while outer < 5:
		var inner := 0
		while inner < 3:
			total = total + 1
			inner = inner + 1
		if outer == 2:
			break
		outer = outer + 1
	return total

func while_over_for_continue_reads_iterator() -> int:
	# while + nested for: body reads of refined for-iterator must stay int.
	var total := 0
	var outer := 0
	while outer < 2:
		for i in range(4):
			if i % 2 == 0:
				continue
			total = total + i
		outer = outer + 1
	return total

func nested_for_over_for_break_reads_both() -> int:
	var total := 0
	for i in range(3):
		for j in range(3):
			if j == 2:
				break
			total = total + j
		if i == 1:
			break
		total = total + i
	return total

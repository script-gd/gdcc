class_name WhileBreakContinueSmoke
extends Node

func while_break_before_limit() -> int:
	var total := 0
	var i := 0
	while i < 10:
		if i == 4:
			break
		total = total + i
		i = i + 1
	return total

func while_continue_skips_even() -> int:
	var total := 0
	var i := 0
	while i < 6:
		i = i + 1
		if i % 2 == 0:
			continue
		total = total + i
	return total

func while_break_and_continue_mix() -> int:
	var total := 0
	var i := 0
	while i < 10:
		i = i + 1
		if i == 7:
			break
		if i % 2 == 0:
			continue
		total = total + i
	return total

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

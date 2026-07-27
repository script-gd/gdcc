extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	# 0+1+2+3 = 6
	if int(target.call("for_break_before_sum")) != 6:
		push_error("for_break_before_sum: expected 6, got %d" % int(target.call("for_break_before_sum")))
		ok = false
	# 1+3+5 = 9
	if int(target.call("for_continue_skips_even")) != 9:
		push_error("for_continue_skips_even: expected 9, got %d" % int(target.call("for_continue_skips_even")))
		ok = false
	# 1+3+5 = 9 (break at 7 before adding)
	if int(target.call("for_break_and_continue_mix")) != 9:
		push_error("for_break_and_continue_mix: expected 9, got %d" % int(target.call("for_break_and_continue_mix")))
		ok = false
	# 3*2 = 6
	if int(target.call("nested_for_no_break_product")) != 6:
		push_error(
			"nested_for_no_break_product: expected 6, got %d"
			% int(target.call("nested_for_no_break_product"))
		)
		ok = false
	# per outer: j==0 adds 0 then j==1 breaks; add i for 0,1,2 => 3
	if int(target.call("nested_for_inner_break_reads_iterators")) != 3:
		push_error(
			"nested_for_inner_break_reads_iterators: expected 3, got %d"
			% int(target.call("nested_for_inner_break_reads_iterators"))
		)
		ok = false
	# per outer: j=1+3=4; three outers + (0+1+2) => 15
	if int(target.call("nested_for_continue_reads_inner_iterator")) != 15:
		push_error(
			"nested_for_continue_reads_inner_iterator: expected 15, got %d"
			% int(target.call("nested_for_continue_reads_inner_iterator"))
		)
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

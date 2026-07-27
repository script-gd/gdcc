extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	# 0+1+2+3 = 6
	if int(target.call("while_break_before_limit")) != 6:
		push_error("while_break_before_limit: expected 6, got %d" % int(target.call("while_break_before_limit")))
		ok = false
	# after i+=1 first: 1,3,5 => 9
	if int(target.call("while_continue_skips_even")) != 9:
		push_error("while_continue_skips_even: expected 9, got %d" % int(target.call("while_continue_skips_even")))
		ok = false
	# 1+3+5 = 9 (break when i becomes 7)
	if int(target.call("while_break_and_continue_mix")) != 9:
		push_error(
			"while_break_and_continue_mix: expected 9, got %d"
			% int(target.call("while_break_and_continue_mix"))
		)
		ok = false
	# three outer iters each add 0+1 => 3
	if int(target.call("nested_while_inner_break")) != 3:
		push_error("nested_while_inner_break: expected 3, got %d" % int(target.call("nested_while_inner_break")))
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	# three outer iters each add 0+1 => 3
	if int(target.call("nested_while_inner_break")) != 3:
		push_error("nested_while_inner_break: expected 3, got %d" % int(target.call("nested_while_inner_break")))
		ok = false
	# three outer iters each add 1+3 => 12
	if int(target.call("for_over_while_inner_continue")) != 12:
		push_error(
			"for_over_while_inner_continue: expected 12, got %d"
			% int(target.call("for_over_while_inner_continue"))
		)
		ok = false
	# outer 0,1,2 each add 3 => 9, then break
	if int(target.call("nested_while_outer_break")) != 9:
		push_error(
			"nested_while_outer_break: expected 9, got %d"
			% int(target.call("nested_while_outer_break"))
		)
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

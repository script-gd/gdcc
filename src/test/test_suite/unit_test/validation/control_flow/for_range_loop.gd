extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if int(target.call("range_stop_sum")) != 10:
		push_error("range_stop_sum: expected 10, got %d" % int(target.call("range_stop_sum")))
		ok = false
	if int(target.call("range_start_end_sum")) != 14:
		push_error("range_start_end_sum: expected 14, got %d" % int(target.call("range_start_end_sum")))
		ok = false
	if int(target.call("range_step_sum")) != 18:
		push_error("range_step_sum: expected 18, got %d" % int(target.call("range_step_sum")))
		ok = false
	if int(target.call("range_negative_step_sum")) != 18:
		push_error("range_negative_step_sum: expected 18, got %d" % int(target.call("range_negative_step_sum")))
		ok = false
	if int(target.call("range_zero_step_empty")) != 0:
		push_error("range_zero_step_empty: expected 0, got %d" % int(target.call("range_zero_step_empty")))
		ok = false
	if int(target.call("int_shorthand_sum")) != 6:
		push_error("int_shorthand_sum: expected 6, got %d" % int(target.call("int_shorthand_sum")))
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

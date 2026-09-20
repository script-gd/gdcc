extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if int(target.call("annotated_local")) != 5:
		push_error("annotated_local failed")
		ok = false
	if int(target.call("annotated_param", 5)) != 6:
		push_error("annotated_param failed")
		ok = false
	if int(target.call("is_state_check")) != 1:
		push_error("is_state_check failed")
		ok = false
	if int(target.call("typed_array_sum")) != 5:
		push_error("typed_array_sum failed")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if int(target.call("branch_on_int_arms", true, 5, 0)) != 1:
		push_error("branch_on_int_arms true arm truthy expected 1")
		ok = false
	if int(target.call("branch_on_int_arms", true, 0, 5)) != 0:
		push_error("branch_on_int_arms true arm falsy expected 0")
		ok = false
	if int(target.call("branch_on_int_arms", false, 5, 0)) != 0:
		push_error("branch_on_int_arms false arm falsy expected 0")
		ok = false
	if int(target.call("branch_on_int_arms", false, 0, 5)) != 1:
		push_error("branch_on_int_arms false arm truthy expected 1")
		ok = false
	if int(target.call("branch_on_bool_arms", true, true, false)) != 10:
		push_error("branch_on_bool_arms true arm true expected 10")
		ok = false
	if int(target.call("branch_on_bool_arms", true, false, true)) != 20:
		push_error("branch_on_bool_arms true arm false expected 20")
		ok = false
	if int(target.call("branch_on_bool_arms", false, true, false)) != 20:
		push_error("branch_on_bool_arms false arm false expected 20")
		ok = false
	if int(target.call("branch_on_bool_arms", false, false, true)) != 10:
		push_error("branch_on_bool_arms false arm true expected 10")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

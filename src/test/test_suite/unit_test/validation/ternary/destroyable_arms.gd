extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if target.call("pick_string", true, "alpha", "beta") != "alpha":
		push_error("pick_string true arm expected alpha")
		ok = false
	if target.call("pick_string", false, "alpha", "beta") != "beta":
		push_error("pick_string false arm expected beta")
		ok = false
	if int(target.call("pick_array_size", true)) != 2:
		push_error("pick_array_size true arm expected 2")
		ok = false
	if int(target.call("pick_array_size", false)) != 3:
		push_error("pick_array_size false arm expected 3")
		ok = false
	if int(target.call("pick_array_first", true)) != 10:
		push_error("pick_array_first true arm expected 10")
		ok = false
	if int(target.call("pick_array_first", false)) != 30:
		push_error("pick_array_first false arm expected 30")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

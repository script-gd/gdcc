extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if int(target.call("describe", 0)) != 100:
		push_error("describe(0) failed")
		ok = false
	if int(target.call("describe", 5)) != 200:
		push_error("describe(5) failed")
		ok = false
	if int(target.call("describe", 1)) != -1:
		push_error("describe(1) failed")
		ok = false
	if int(target.call("describe_cross", 0)) != 10:
		push_error("describe_cross(0) failed")
		ok = false
	if int(target.call("describe_cross", 3)) != 30:
		push_error("describe_cross(3) failed")
		ok = false
	if int(target.call("describe_cross", 9)) != -1:
		push_error("describe_cross(9) failed")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if int(target.call("read_current")) != 5:
		push_error("read_current failed")
		ok = false
	if int(target.call("read_speed")) != 4:
		push_error("read_speed failed")
		ok = false
	if int(target.call("with_default")) != 5:
		push_error("with_default() failed")
		ok = false
	if int(target.call("with_default", 9)) != 9:
		push_error("with_default(9) failed")
		ok = false
	if int(target.call("with_bare_default")) != 2:
		push_error("with_bare_default() failed")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

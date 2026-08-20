extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if target.call("picks_flat", true) != true:
		push_error("picks_flat true arm expected flat node")
		ok = false
	if target.call("picks_flat", false) != false:
		push_error("picks_flat false arm expected spatial node")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

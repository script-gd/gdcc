extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if target.call("null_when_false", true) != false:
		push_error("null_when_false true arm expected non-null")
		ok = false
	if target.call("null_when_false", false) != true:
		push_error("null_when_false false arm expected null")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

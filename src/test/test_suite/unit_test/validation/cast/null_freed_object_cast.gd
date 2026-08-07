extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if target.call("null_literal_as_node") != true:
		push_error("null_literal_as_node expected true")
		ok = false
	if target.call("freed_node_as_node2d") != true:
		push_error("freed_node_as_node2d expected true")
		ok = false
	if target.call("freed_node_as_node") != true:
		push_error("freed_node_as_node expected true")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

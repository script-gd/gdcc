extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if target.call("upcast_node2d") != true:
		push_error("upcast_node2d expected true")
		ok = false
	if target.call("downcast_success") != true:
		push_error("downcast_success expected true")
		ok = false
	if target.call("downcast_null") != true:
		push_error("downcast_null expected true")
		ok = false
	if target.call("null_as_node") != true:
		push_error("null_as_node expected true")
		ok = false
	if target.call("same_type") != true:
		push_error("same_type expected true")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

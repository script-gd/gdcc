extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if target.call("object_exact") != true:
		push_error("object_exact expected true")
		ok = false
	if target.call("object_upcast") != true:
		push_error("object_upcast expected true")
		ok = false
	if target.call("null_is_object") != false:
		push_error("null_is_object expected false")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

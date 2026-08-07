extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if float(target.call("cast_local_return", 5)) != 5.0:
		push_error("cast_local_return expected 5.0")
		ok = false
	if float(target.call("cast_call_arg", 4)) != 5.0:
		push_error("cast_call_arg expected 5.0")
		ok = false
	if float(target.call("cast_member_access")) != 3.0:
		push_error("cast_member_access expected 3.0")
		ok = false

	var node2d := Node2D.new()
	if int(target.call("cast_condition", node2d)) != 1:
		push_error("cast_condition(Node2D) expected 1")
		ok = false
	node2d.free()

	var node := Node.new()
	if int(target.call("cast_condition", node)) != 0:
		push_error("cast_condition(Node) expected 0")
		ok = false
	node.free()

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

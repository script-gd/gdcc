extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if float(target.call("int_else_float", true, 3)) != 3.0:
		push_error("int_else_float true arm expected 3.0")
		ok = false
	if float(target.call("int_else_float", false, 3)) != 2.5:
		push_error("int_else_float false arm expected 2.5")
		ok = false
	if float(target.call("float_else_int", true, 3)) != 1.5:
		push_error("float_else_int true arm expected 1.5")
		ok = false
	if float(target.call("float_else_int", false, 3)) != 3.0:
		push_error("float_else_int false arm expected 3.0")
		ok = false
	if int(target.call("variant_merge", true)) != 7:
		push_error("variant_merge true arm expected 7")
		ok = false
	if String(target.call("variant_merge", false)) != "text":
		push_error("variant_merge false arm expected text")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

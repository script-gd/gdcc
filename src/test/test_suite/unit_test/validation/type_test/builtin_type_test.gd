extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if target.call("exact_builtin_match") != true:
		push_error("exact_builtin_match expected true")
		ok = false
	if target.call("builtin_mismatch") != false:
		push_error("builtin_mismatch expected false")
		ok = false
	if target.call("string_match") != true:
		push_error("string_match expected true")
		ok = false
	if target.call("float_match") != true:
		push_error("float_match expected true")
		ok = false
	if target.call("int_is_float") != false:
		push_error("int_is_float expected false")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

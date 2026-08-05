extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if target.call("variant_builtin_runtime", 42) != true:
		push_error("variant_builtin_runtime(42) expected true")
		ok = false
	if target.call("variant_builtin_runtime", "hello") != false:
		push_error("variant_builtin_runtime('hello') expected false")
		ok = false
	if target.call("variant_object_runtime", target) != true:
		push_error("variant_object_runtime(node) expected true")
		ok = false
	if target.call("variant_object_runtime", 42) != false:
		push_error("variant_object_runtime(42) expected false")
		ok = false
	if target.call("variant_string_runtime", "hello") != true:
		push_error("variant_string_runtime('hello') expected true")
		ok = false
	if target.call("variant_string_runtime", 42) != false:
		push_error("variant_string_runtime(42) expected false")
		ok = false

	# Phase 7 top-type: is Variant always true; is not Variant always false.
	if target.call("is_variant_int", 42) != true:
		push_error("is_variant_int(42) expected true")
		ok = false
	if target.call("is_variant_null") != true:
		push_error("is_variant_null() expected true")
		ok = false
	if target.call("is_variant_node", target) != true:
		push_error("is_variant_node(node) expected true")
		ok = false
	if target.call("is_variant_operand", 7) != true:
		push_error("is_variant_operand(7) expected true")
		ok = false
	if target.call("is_not_variant_int", 42) != false:
		push_error("is_not_variant_int(42) expected false")
		ok = false
	if target.call("is_not_variant_null") != false:
		push_error("is_not_variant_null() expected false")
		ok = false
	if target.call("is_not_variant_node", target) != false:
		push_error("is_not_variant_node(node) expected false")
		ok = false
	if target.call("is_not_variant_operand", 7) != false:
		push_error("is_not_variant_operand(7) expected false")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

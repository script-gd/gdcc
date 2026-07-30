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

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

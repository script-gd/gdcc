extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true

	var arr := [1, 2, 3, 4, 5]
	if int(target.call("array_sum", arr)) != 15:
		push_error("array_sum: expected 15, got %d" % int(target.call("array_sum", arr)))
		ok = false

	var parts := ["hello", " ", "world"]
	if String(target.call("array_string_join", parts)) != "hello world":
		push_error("array_string_join: expected 'hello world', got '%s'" % String(target.call("array_string_join", parts)))
		ok = false

	var table := {10: "a", 20: "b", 30: "c"}
	if int(target.call("dictionary_key_sum", table)) != 60:
		push_error("dictionary_key_sum: expected 60, got %d" % int(target.call("dictionary_key_sum", table)))
		ok = false

	var rows := [[1, 2], [3, 4]]
	if int(target.call("nested_loop_product", rows)) != 10:
		push_error("nested_loop_product: expected 10, got %d" % int(target.call("nested_loop_product", rows)))
		ok = false

	var mixed := [3, -2, 7, -1, 4]
	if int(target.call("conditional_accumulate", mixed)) != 1400 + (-3):
		push_error("conditional_accumulate: expected 1397, got %d" % int(target.call("conditional_accumulate", mixed)))
		ok = false

	if int(target.call("multi_pass_transform", [1, 2, 3])) != 30:
		push_error("multi_pass_transform: expected 30, got %d" % int(target.call("multi_pass_transform", [1, 2, 3])))
		ok = false

	var lookup_table := {1: 100, 2: 200, 3: 300}
	var lookup_keys := [1, 3, 2]
	if int(target.call("dictionary_value_lookup", lookup_table, lookup_keys)) != 600:
		push_error("dictionary_value_lookup: expected 600, got %d" % int(target.call("dictionary_value_lookup", lookup_table, lookup_keys)))
		ok = false

	var matrix := [[1, 8], [5, 2]]
	if int(target.call("nested_with_conditional", matrix)) != 130 + 3:
		push_error("nested_with_conditional: expected 133, got %d" % int(target.call("nested_with_conditional", matrix)))
		ok = false

	if String(target.call("string_build_from_arrays", ["a", "b"], ["1", "2"])) != "a1a2b1b2":
		push_error("string_build_from_arrays: expected 'a1a2b1b2', got '%s'" % String(target.call("string_build_from_arrays", ["a", "b"], ["1", "2"])))
		ok = false

	var values := [2, 3, 4]
	var weights := [10, 20, 30]
	if int(target.call("weighted_sum", values, weights)) != 200:
		push_error("weighted_sum: expected 200, got %d" % int(target.call("weighted_sum", values, weights)))
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

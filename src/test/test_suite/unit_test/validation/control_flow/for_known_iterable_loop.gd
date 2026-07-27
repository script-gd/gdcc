extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if String(target.call("rebuild_string", "for-in")) != "for-in":
		push_error("rebuild_string: expected 'for-in'.")
		ok = false
	if String(target.call("rebuild_string", "")) != "":
		push_error("rebuild_string: expected empty string for empty input.")
		ok = false

	var values: Array[int] = [4, -1, 7]
	if int(target.call("typed_array_sum", values)) != 10:
		push_error("typed_array_sum: expected 10.")
		ok = false
	var empty_values: Array[int] = []
	if int(target.call("typed_array_sum", empty_values)) != 0:
		push_error("typed_array_sum: expected 0 for empty input.")
		ok = false

	var table: Dictionary[String, int] = {"aa": 100, "bbb": 200}
	if int(target.call("typed_dictionary_key_length_sum", table)) != 5:
		push_error("typed_dictionary_key_length_sum: expected 5.")
		ok = false
	var empty_table: Dictionary[String, int] = {}
	if int(target.call("typed_dictionary_key_length_sum", empty_table)) != 0:
		push_error("typed_dictionary_key_length_sum: expected 0 for empty input.")
		ok = false

	var packed_values := PackedInt32Array([4, -1, 7])
	if int(target.call("packed_int_sum", packed_values)) != 10:
		push_error("packed_int_sum: expected 10.")
		ok = false
	var empty_packed := PackedInt32Array()
	if int(target.call("packed_int_sum", empty_packed)) != 0:
		push_error("packed_int_sum: expected 0 for empty input.")
		ok = false

	var packed_strings := PackedStringArray(["a", "bc"])
	if String(target.call("packed_string_join", packed_strings)) != "abc":
		push_error("packed_string_join: expected 'abc'.")
		ok = false
	var empty_packed_strings := PackedStringArray()
	if String(target.call("packed_string_join", empty_packed_strings)) != "":
		push_error("packed_string_join: expected empty string for empty input.")
		ok = false

	if float(target.call("float_shorthand_sum", 3.5)) != 6.0:
		push_error("float_shorthand_sum: expected 6.0 for 3.5.")
		ok = false
	if float(target.call("float_shorthand_sum", 3.0)) != 3.0:
		push_error("float_shorthand_sum: expected 3.0 for exact integer bound 3.0.")
		ok = false
	if float(target.call("float_shorthand_sum", 0.0)) != 0.0:
		push_error("float_shorthand_sum: expected 0.0 for 0.0.")
		ok = false
	if float(target.call("float_shorthand_sum", -0.5)) != 0.0:
		push_error("float_shorthand_sum: expected 0.0 for -0.5.")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

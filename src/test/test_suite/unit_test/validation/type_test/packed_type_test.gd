extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if target.call("packed_match") != true:
		push_error("packed_match expected true")
		ok = false
	if target.call("packed_mismatch") != false:
		push_error("packed_mismatch expected false")
		ok = false
	if target.call("packed_string_match") != true:
		push_error("packed_string_match expected true")
		ok = false
	if target.call("packed_is_not_other") != true:
		push_error("packed_is_not_other expected true")
		ok = false
	if target.call("packed_is_not_bare_array") != true:
		push_error("packed_is_not_bare_array expected true")
		ok = false
	if target.call("packed_byte_match") != true:
		push_error("packed_byte_match expected true")
		ok = false
	if target.call("variant_packed_runtime", PackedInt32Array([1, 2])) != true:
		push_error("variant_packed_runtime(packed) expected true")
		ok = false
	if target.call("variant_packed_runtime", 42) != false:
		push_error("variant_packed_runtime(42) expected false")
		ok = false
	if target.call("variant_packed_runtime", PackedFloat32Array()) != false:
		push_error("variant_packed_runtime(PackedFloat32Array) expected false")
		ok = false
	if target.call("variant_packed_string_runtime", PackedStringArray()) != true:
		push_error("variant_packed_string_runtime(packed string) expected true")
		ok = false
	if target.call("variant_packed_string_runtime", "hello") != false:
		push_error("variant_packed_string_runtime(string) expected false")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if int(target.call("generic_create_lookup_mutation")) != 2652:
		push_error("generic_create_lookup_mutation failed")
		ok = false
	if int(target.call("empty_mixed_nested_and_immediate")) != 47:
		push_error("empty_mixed_nested_and_immediate failed")
		ok = false
	if int(target.call("typed_local_assign_return_and_call")) != 299:
		push_error("typed_local_assign_return_and_call failed")
		ok = false
	if int(target.call("duplicate_key_overwrite_and_order")) != 2921:
		push_error("duplicate_key_overwrite_and_order failed")
		ok = false
	if int(target.call("string_stringname_key_roundtrip")) != 1422:
		push_error("string_stringname_key_roundtrip failed")
		ok = false
	if int(target.call("typed_property_seed")) != 111:
		push_error("typed_property_seed failed")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

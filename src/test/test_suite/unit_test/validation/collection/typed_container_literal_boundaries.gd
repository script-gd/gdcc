extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if int(target.call("typed_array_int_to_float_local")) != 313:
		push_error("typed_array_int_to_float_local failed")
		ok = false
	if int(target.call("typed_dict_int_to_float_local")) != 212:
		push_error("typed_dict_int_to_float_local failed")
		ok = false
	if int(target.call("variant_element_unpack_to_typed")) != 256:
		push_error("variant_element_unpack_to_typed failed")
		ok = false
	if int(target.call("object_element_lifecycle")) != 11:
		push_error("object_element_lifecycle failed")
		ok = false
	# array size=2, array[1]=2, dict size=1, dict["a"]=1 -> 2000+200+10+1
	if int(target.call("property_initializer_seeds")) != 2211:
		push_error("property_initializer_seeds failed")
		ok = false
	if int(target.call("typed_exact_call_and_return")) != 34:
		push_error("typed_exact_call_and_return failed")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

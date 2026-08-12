extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	# values=[2,9,6], total=17 -> 3*1000 + 17*10 + 9
	if int(target.call("generic_create_size_subscript_mutation")) != 3179:
		push_error("generic_create_size_subscript_mutation failed")
		ok = false
	# empty=0, mixed=3, nested=2, nested[0][1]=2, immediate=20
	if int(target.call("empty_and_mixed_and_nested")) != 32220:
		push_error("empty_and_mixed_and_nested failed")
		ok = false
	# local size=3, take([7,8])=9, return_typed[0]=9 -> 300+90+9
	if int(target.call("typed_local_assign_return_and_call")) != 399:
		push_error("typed_local_assign_return_and_call failed")
		ok = false
	if int(target.call("variant_pack_and_discard_side_effect")) != 121:
		push_error("variant_pack_and_discard_side_effect failed")
		ok = false
	# property [10,20,30] -> 3*100 + 10 + 30
	if int(target.call("typed_property_seed")) != 340:
		push_error("typed_property_seed failed")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

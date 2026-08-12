extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if int(target.call("for_in_array_literal_sum")) != 20:
		push_error("for_in_array_literal_sum failed")
		ok = false
	if int(target.call("for_in_empty_array_literal")) != 1:
		push_error("for_in_empty_array_literal failed")
		ok = false
	# sum=9, size=3 -> 90+3
	if int(target.call("for_in_typed_array_literal")) != 93:
		push_error("for_in_typed_array_literal failed")
		ok = false
	# 1+2+3+4+5+6 = 21
	if int(target.call("for_in_nested_array_literal")) != 21:
		push_error("for_in_nested_array_literal failed")
		ok = false
	# size=3; key lengths 1+2+3=6; values 2+5+7=14 -> 3000+600+14
	if int(target.call("for_in_dict_literal_keys_and_values")) != 3614:
		push_error("for_in_dict_literal_keys_and_values failed")
		ok = false
	if int(target.call("for_in_empty_dict_literal")) != 0:
		push_error("for_in_empty_dict_literal failed")
		ok = false
	# groups size 2; total 1+2+3+4+5=15 -> 200+15
	if int(target.call("for_in_dict_of_array_literal")) != 215:
		push_error("for_in_dict_of_array_literal failed")
		ok = false
	# rows size 3; total 1+2+4=7 -> 300+7
	if int(target.call("for_in_array_of_dict_literal")) != 307:
		push_error("for_in_array_of_dict_literal failed")
		ok = false
	# log size 3 order 1,2,3; total 6 -> 3000+100+20+3+6
	if int(target.call("for_in_literal_element_side_effects")) != 3129:
		push_error("for_in_literal_element_side_effects failed")
		ok = false
	# 1 + skip2 + 3 + 4 + break at 5 = 8
	if int(target.call("for_in_array_literal_break_continue")) != 8:
		push_error("for_in_array_literal_break_continue failed")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

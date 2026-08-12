extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	# values.size=3, log=[1,2,3], values[2]=3 -> 3000+100+20+3+3 = 3126
	if int(target.call("array_left_to_right")) != 3126:
		push_error("array_left_to_right failed")
		ok = false
	# scores.size=2, log=[1,2,3,4], scores[1]=2, scores[3]=4 -> 20000+1000+200+30+4+2+4 = 21240
	if int(target.call("dictionary_key_value_order")) != 21240:
		push_error("dictionary_key_value_order failed")
		ok = false
	# log=[5,6,7,8] -> 4000+500+60+7+8 = 4575
	if int(target.call("discarded_literal_keeps_side_effects")) != 4575:
		push_error("discarded_literal_keeps_side_effects failed")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if int(target.call("pick_int", true, 7, 9)) != 7:
		push_error("pick_int true arm expected 7")
		ok = false
	if int(target.call("pick_int", false, 7, 9)) != 9:
		push_error("pick_int false arm expected 9")
		ok = false
	if target.call("pick_string", true) != "yes":
		push_error("pick_string true arm expected yes")
		ok = false
	if target.call("pick_string", false) != "no":
		push_error("pick_string false arm expected no")
		ok = false
	if target.call("pick_bool", true, false) != false:
		push_error("pick_bool true arm expected other=false")
		ok = false
	if target.call("pick_bool", false, false) != true:
		push_error("pick_bool false arm expected not other=true")
		ok = false
	if int(target.call("consume_selected", true, 5)) != 16:
		push_error("consume_selected true arm expected take(5)+1=16")
		ok = false
	if int(target.call("consume_selected", false, 5)) != 11:
		push_error("consume_selected false arm expected take(0)+1=11")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

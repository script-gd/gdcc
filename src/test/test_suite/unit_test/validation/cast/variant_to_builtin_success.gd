extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if int(target.call("cast_to_int", 11)) != 11:
		push_error("cast_to_int(11) expected 11")
		ok = false
	if int(target.call("cast_to_int", 4.2)) != 4:
		push_error("cast_to_int(4.2) expected 4")
		ok = false
	if float(target.call("cast_to_float", 8)) != 8.0:
		push_error("cast_to_float(8) expected 8.0")
		ok = false
	if target.call("cast_to_bool", 1) != true:
		push_error("cast_to_bool(1) expected true")
		ok = false
	if target.call("cast_to_bool", 0) != false:
		push_error("cast_to_bool(0) expected false")
		ok = false
	if int(target.call("cast_string_to_int", "77")) != 77:
		push_error("cast_string_to_int('77') expected 77")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

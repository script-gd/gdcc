extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if int(target.call("identity_int", 7)) != 7:
		push_error("identity_int expected 7")
		ok = false
	if float(target.call("int_to_float", 3)) != 3.0:
		push_error("int_to_float expected 3.0")
		ok = false
	if int(target.call("float_to_int", 3.9)) != 3:
		push_error("float_to_int expected 3")
		ok = false
	if target.call("int_to_bool_true", 5) != true:
		push_error("int_to_bool_true expected true")
		ok = false
	if target.call("int_to_bool_false", 0) != false:
		push_error("int_to_bool_false expected false")
		ok = false
	if int(target.call("string_to_int", "123")) != 123:
		push_error("string_to_int expected 123")
		ok = false
	if float(target.call("string_to_float", "2.5")) != 2.5:
		push_error("string_to_float expected 2.5")
		ok = false
	if int(target.call("as_variant", 9)) != 9:
		push_error("as_variant expected 9")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

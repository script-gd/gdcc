extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if int(target.call("variant_condition", true, 5, 9)) != 5:
		push_error("variant_condition truthy bool expected yes")
		ok = false
	if int(target.call("variant_condition", false, 5, 9)) != 9:
		push_error("variant_condition falsy bool expected no")
		ok = false
	if int(target.call("variant_condition", 3, 5, 9)) != 5:
		push_error("variant_condition truthy int expected yes")
		ok = false
	if int(target.call("variant_condition", 0, 5, 9)) != 9:
		push_error("variant_condition falsy int expected no")
		ok = false
	if int(target.call("int_condition", 5)) != 100:
		push_error("int_condition truthy expected 100")
		ok = false
	if int(target.call("int_condition", 0)) != 200:
		push_error("int_condition falsy expected 200")
		ok = false
	if int(target.call("float_condition", 2.5)) != 1:
		push_error("float_condition truthy expected 1")
		ok = false
	if int(target.call("float_condition", 0.0)) != 0:
		push_error("float_condition falsy expected 0")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

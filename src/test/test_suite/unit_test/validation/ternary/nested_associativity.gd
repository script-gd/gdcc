extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if int(target.call("right_associative", true, true)) != 1:
		push_error("right_associative c1=true expected 1")
		ok = false
	if int(target.call("right_associative", true, false)) != 1:
		push_error("right_associative c1=true expected 1 regardless of c2")
		ok = false
	if int(target.call("right_associative", false, true)) != 2:
		push_error("right_associative c1=false c2=true expected 2")
		ok = false
	if int(target.call("right_associative", false, false)) != 3:
		push_error("right_associative c1=false c2=false expected 3")
		ok = false
	if int(target.call("left_grouped", true, true)) != 10:
		push_error("left_grouped c2=true c1=true expected 10")
		ok = false
	if int(target.call("left_grouped", false, true)) != 20:
		push_error("left_grouped c2=true c1=false expected 20")
		ok = false
	if int(target.call("left_grouped", true, false)) != 30:
		push_error("left_grouped c2=false expected 30")
		ok = false
	if int(target.call("left_grouped", false, false)) != 30:
		push_error("left_grouped c2=false expected 30 regardless of c1")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

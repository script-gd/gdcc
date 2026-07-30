extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if target.call("negated_exact_builtin") != false:
		push_error("negated_exact_builtin expected false")
		ok = false
	if target.call("negated_mismatch") != true:
		push_error("negated_mismatch expected true")
		ok = false
	if target.call("negated_null_object") != true:
		push_error("negated_null_object expected true")
		ok = false
	if target.call("negated_upcast") != false:
		push_error("negated_upcast expected false")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

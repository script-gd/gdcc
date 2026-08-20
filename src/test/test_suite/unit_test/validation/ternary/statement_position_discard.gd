extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if int(target.call("run_discard", true)) != 1:
		push_error("run_discard true arm expected exactly one recorded mark")
		ok = false
	if target.call("last_mark") != "selected":
		push_error("run_discard true arm expected selected mark")
		ok = false
	if int(target.call("run_discard", false)) != 1:
		push_error("run_discard false arm expected exactly one recorded mark")
		ok = false
	if target.call("last_mark") != "skipped":
		push_error("run_discard false arm expected skipped mark")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	if int(target.call("subscript_jump")) != 5:
		push_error("subscript_jump failed")
		ok = false
	if int(target.call("key_count")) != 3:
		push_error("key_count failed")
		ok = false
	if String(target.call("first_key")) != "IDLE":
		push_error("first_key failed")
		ok = false

	var group: Dictionary = target.call("group_roundtrip")
	if group.size() != 3:
		push_error("group_roundtrip size failed: " + str(group.size()))
		ok = false
	elif group.keys() != ["IDLE", "JUMP", "FALL"]:
		push_error("group_roundtrip key order failed: " + str(group.keys()))
		ok = false
	elif int(group["IDLE"]) != 0 or int(group["JUMP"]) != 5 or int(group["FALL"]) != 6:
		push_error("group_roundtrip values failed")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

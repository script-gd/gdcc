extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var typed_arr: Array[int] = [1, 2, 3]
	var bare_arr: Array = [4, 5, 6]
	var typed_dict: Dictionary[String, int] = {"a": 1}
	var bare_dict: Dictionary = {"b": 2}

	var ok := true
	# Genuinely bare runtime values: parameterized branches (+4) stay false.
	var arr_mask = int(target.call("array_type_mask", typed_arr, bare_arr))
	if arr_mask != 3:
		push_error("array_type_mask expected 3 (typed match + bare match), got %d" % arr_mask)
		ok = false

	var dict_mask = int(target.call("dict_type_mask", typed_dict, bare_dict))
	if dict_mask != 3:
		push_error("dict_type_mask expected 3 (typed match + bare match), got %d" % dict_mask)
		ok = false

	# Typed values passed into bare slots must still pass parameterized `is` via runtime metadata.
	var typed_in_bare = int(target.call("bare_slot_typed_metadata_mask", typed_arr, typed_dict))
	if typed_in_bare != 3:
		push_error("bare_slot_typed_metadata_mask expected 3 (typed metadata in bare slots), got %d" % typed_in_bare)
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

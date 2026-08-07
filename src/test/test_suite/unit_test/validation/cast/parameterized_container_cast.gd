extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var plain: Array = [1, 2, 3]
	var typed: Array[int] = [4, 5]
	var plain_dict: Dictionary = {"a": 1}

	target.call("store_plain_as_typed", plain, 0)
	var as_typed: Array = target.last_array
	# Base-only cast: static target is Array[int], runtime metadata stays untyped plain Array.
	if as_typed.is_typed() or as_typed.size() != 3 or int(as_typed[0]) != 1:
		push_error("store_plain_as_typed must keep plain runtime metadata")
		return

	target.call("store_typed_as_plain", typed, "x")
	var as_plain: Array = target.last_array
	if as_plain.get_typed_builtin() != TYPE_INT or as_plain.size() != 2 or int(as_plain[1]) != 5:
		push_error("store_typed_as_plain must preserve source typed metadata")
		return

	target.call("store_typed_as_other", typed, 0.0)
	var retargeted: Array = target.last_array
	# Godot-compatible base cast does not rewrite typed metadata to Array[String].
	if retargeted.get_typed_builtin() != TYPE_INT or retargeted.size() != 2:
		push_error("store_typed_as_other must not rewrite typed metadata")
		return

	target.call("store_plain_dict_as_typed", plain_dict, 1)
	var as_typed_dict: Dictionary = target.last_dict
	if as_typed_dict.is_typed() or int(as_typed_dict["a"]) != 1:
		push_error("store_plain_dict_as_typed must keep plain runtime metadata")
		return

	print("__UNIT_TEST_PASS_MARKER__")

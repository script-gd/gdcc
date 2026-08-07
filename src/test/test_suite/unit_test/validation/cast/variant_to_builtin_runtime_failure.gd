# gdcc-test: output_contains=godot_variant_construct failed for builtin_cast to 'int'
extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	# Failure path prints a stable runtime error, then returns the default int (0).
	var result = target.call("cast_vector_to_int", Vector2(1.0, 2.0))
	if int(result) != 0:
		push_error("cast_vector_to_int failure expected default 0, got %s" % str(result))
		return
	print("__UNIT_TEST_PASS_MARKER__")

extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var expected := {
		"idle": 0,
		"jump": 5,
		"fall": 6,
		"negative": -2,
		"mask": 8,
		"after_mask": 9,
		"copy_of_jump": 5,
	}
	var ok := true
	for method in expected:
		var actual = int(target.call(method))
		if actual != expected[method]:
			push_error(method + " expected " + str(expected[method]) + " but got " + str(actual))
			ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

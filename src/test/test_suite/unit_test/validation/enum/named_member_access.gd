extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	# State = { IDLE: 0, JUMP: 5, FALL: 6 }; count_to_jump sums 0+1+2+3+4.
	var expected := {
		"folded_jump": 5,
		"arithmetic": 4,
		"comparison": 1,
		"count_to_jump": 10,
	}
	var ok := true
	for method in expected:
		var actual = int(target.call(method))
		if actual != expected[method]:
			push_error(method + " expected " + str(expected[method]) + " but got " + str(actual))
			ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

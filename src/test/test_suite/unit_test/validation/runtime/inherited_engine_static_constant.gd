# gdcc-test: output_not_contains=engine method call failed: Node2D.NOTIFICATION_ENTER_TREE
extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var expected = Node2D.NOTIFICATION_ENTER_TREE
	if int(target.call("read_enter_tree_notification")) != int(expected):
		push_error("Inherited Node2D.NOTIFICATION_ENTER_TREE static load returned wrong value.")
		return

	if int(target.call("read_enter_tree_notification_property")) != int(expected):
		push_error("Inherited Node2D.NOTIFICATION_ENTER_TREE property initializer returned wrong value.")
		return

	print("__UNIT_TEST_PASS_MARKER__")

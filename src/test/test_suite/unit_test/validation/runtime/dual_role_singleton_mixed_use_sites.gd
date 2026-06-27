# gdcc-test: output_not_contains=engine method call failed: Input.is_action_pressed
# gdcc-test: output_not_contains=engine method call failed: IP.RESOLVER_MAX_QUERIES
extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	if int(target.call("mixed_routes_in_one_body")) != 256:
		push_error("Dual-role mixed use-sites polluted each other in the same body.")
		return

	var pressed_state = target.call("read_input_pressed_state")
	if typeof(pressed_state) != TYPE_BOOL:
		push_error("Dual-role Input.is_action_pressed returned non-bool: %d" % [typeof(pressed_state)])
		return

	if int(target.call("read_ip_resolver_max_queries")) != 256:
		push_error("Dual-role IP.RESOLVER_MAX_QUERIES static load returned wrong value.")
		return

	print("__UNIT_TEST_PASS_MARKER__")

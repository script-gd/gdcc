# gdcc-test: output_not_contains=engine method call failed: IP.RESOLVER_MAX_QUERIES
# gdcc-test: output_not_contains=engine method call failed: IP.RESOLVER_INVALID_ID
# gdcc-test: output_not_contains=engine method call failed: ResourceUID.INVALID_ID
# gdcc-test: output_not_contains=engine method call failed: DisplayServer.MAIN_WINDOW_ID
extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	if int(target.call("read_resolver_max_queries")) != 256:
		push_error("Dual-role IP.RESOLVER_MAX_QUERIES static load returned wrong value.")
		return

	if int(target.call("read_resolver_invalid_id")) != -1:
		push_error("Dual-role IP.RESOLVER_INVALID_ID static load returned wrong value.")
		return

	if int(target.call("read_resource_uid_invalid_id")) != -1:
		push_error("Dual-role ResourceUID.INVALID_ID static load returned wrong value.")
		return

	if int(target.call("read_display_server_main_window_id")) != 0:
		push_error("Dual-role DisplayServer.MAIN_WINDOW_ID static load returned wrong value.")
		return

	if int(target.call("read_startup_resolver_queries")) != 256:
		push_error("Dual-role property initializer IP.RESOLVER_MAX_QUERIES returned wrong value.")
		return

	print("__UNIT_TEST_PASS_MARKER__")

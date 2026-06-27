class_name DualRoleSingletonMixedUseSitesSmoke
extends Node

func mixed_routes_in_one_body() -> int:
	# Two dual-role names in the same function body, exercising both Step 9 routes.
	# Input.is_action_pressed(...) stays SINGLETON (instance call via CallMethodInsn);
	# IP.RESOLVER_MAX_QUERIES switches to TYPE_META (engine class constant static load).
	# If the dual-role bias polluted the scope, one expression would fail to lower.
	var pressed: bool = Input.is_action_pressed(&"ui_accept", true)
	var queries: int = IP.RESOLVER_MAX_QUERIES
	if queries != 256:
		return -1
	# pressed is environment-dependent; reference it so the SINGLETON instance
	# call use-site stays live alongside the TYPE_META static load use-site.
	if pressed:
		return queries
	return queries

func read_input_pressed_state() -> bool:
	# Singleton instance call on a dual-role name: SINGLETON head + CallMethodInsn.
	return Input.is_action_pressed(&"ui_accept", true)

func read_ip_resolver_max_queries() -> int:
	# Static load on a dual-role name: TYPE_META head (Step 9 dual-role bias).
	return IP.RESOLVER_MAX_QUERIES

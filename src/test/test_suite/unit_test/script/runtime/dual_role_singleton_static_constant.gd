class_name DualRoleSingletonStaticConstantSmoke
extends Node

# Property initializer exercising the dual-role TYPE_META static load route.
# IP is a dual-role name (singleton in value namespace, engine class in type-meta
# namespace). RESOLVER_MAX_QUERIES only resolves in the type-meta static namespace,
# so the chain head must publish TYPE_META per Step 9 dual-role bias.
var startup_resolver_queries: int = IP.RESOLVER_MAX_QUERIES

func read_resolver_max_queries() -> int:
	# Dual-role TYPE_META static load in executable body.
	return IP.RESOLVER_MAX_QUERIES

func read_resolver_invalid_id() -> int:
	# Another IP class constant on the same dual-role singleton.
	return IP.RESOLVER_INVALID_ID

func read_resource_uid_invalid_id() -> int:
	# ResourceUID is also a dual-role singleton; INVALID_ID is a class constant.
	return ResourceUID.INVALID_ID

func read_display_server_main_window_id() -> int:
	# DisplayServer is dual-role; MAIN_WINDOW_ID is a class constant with value 0.
	return DisplayServer.MAIN_WINDOW_ID

func read_startup_resolver_queries() -> int:
	return startup_resolver_queries

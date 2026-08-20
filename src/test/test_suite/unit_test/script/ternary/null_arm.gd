class_name TernaryNullArm
extends Node

func null_when_false(flag: bool) -> bool:
	var node := Node2D.new()
	var chosen: Node2D = node if flag else null
	var is_null := chosen == null
	node.free()
	return is_null

class_name ObjectTypeTest
extends Node

func object_exact() -> bool:
	return self is Node

func object_upcast() -> bool:
	var child := Node2D.new()
	var result := child is Node
	child.free()
	return result

func null_is_object() -> bool:
	var missing: Node = null
	return missing is Node

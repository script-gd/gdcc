class_name IsNotTest
extends Node

func negated_exact_builtin() -> bool:
	var x := 1
	return x is not int

func negated_mismatch() -> bool:
	var x := 1.0
	return x is not int

func negated_null_object() -> bool:
	var missing: Node = null
	return missing is not Node

func negated_upcast() -> bool:
	var child := Node2D.new()
	var result := child is not Node
	child.free()
	return result

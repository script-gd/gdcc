class_name CastEngineObject
extends Node

func upcast_node2d() -> bool:
	var child := Node2D.new()
	var result := child as Node
	var ok := result != null
	child.free()
	return ok

func downcast_success() -> bool:
	var child := Node2D.new()
	var as_node: Node = child
	var result := (as_node as Node2D) != null
	child.free()
	return result

func downcast_null() -> bool:
	var node := Node.new()
	var result := (node as Node2D) == null
	node.free()
	return result

func null_as_node() -> bool:
	var missing: Node = null
	return (missing as Node) == null

func same_type() -> bool:
	var node := Node.new()
	var result := (node as Node) != null
	node.free()
	return result

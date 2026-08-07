class_name CastNullFreedObject
extends Node

func null_literal_as_node() -> bool:
	return (null as Node) == null

func freed_node_as_node2d() -> bool:
	var node := Node2D.new()
	node.free()
	return (node as Node2D) == null

func freed_node_as_node() -> bool:
	var node := Node.new()
	node.free()
	return (node as Node) == null

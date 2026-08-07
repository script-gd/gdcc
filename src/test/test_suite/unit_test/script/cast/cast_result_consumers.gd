class_name CastResultConsumers
extends Node

func cast_local_return(value: int) -> float:
	var converted := value as float
	return converted

func cast_call_arg(value: int) -> float:
	return take_float(value as float)

func take_float(value: float) -> float:
	return value + 1.0

func cast_member_access() -> float:
	var node := Node2D.new()
	node.position = Vector2(3.0, 4.0)
	var x := (node as Node2D).position.x
	node.free()
	return x

func cast_condition(value: Node) -> int:
	if value as Node2D:
		return 1
	return 0

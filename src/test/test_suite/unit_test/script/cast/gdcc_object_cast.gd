class_name CastGdccObject
extends Node

class Base extends RefCounted:
	var tag: int = 0

class Child extends Base:
	func mark(value: int) -> void:
		tag = value

func upcast_child() -> Base:
	var child: Child = Child.new()
	child.mark(9)
	return child as Base

func downcast_success() -> int:
	var child: Child = Child.new()
	child.mark(5)
	var as_base: Base = child
	var recovered: Child = as_base as Child
	if recovered == null:
		return -1
	return recovered.tag

func downcast_null() -> bool:
	var base: Base = Base.new()
	return (base as Child) == null

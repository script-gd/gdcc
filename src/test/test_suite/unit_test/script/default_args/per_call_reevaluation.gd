class_name DefaultArgsPerCallReevaluation
extends Node

var counter: int = 0

func next_id() -> int:
	counter += 1
	return counter

func take(tag: int = next_id()) -> int:
	return tag

func append_probe(items: Array[int] = [0]) -> int:
	items.append(1)
	return items.size() * 10 + items[0]

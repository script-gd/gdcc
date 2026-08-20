class_name TernaryBasicSameType
extends Node

func pick_int(flag: bool, yes: int, no: int) -> int:
	return yes if flag else no

func pick_string(flag: bool) -> String:
	return "yes" if flag else "no"

func pick_bool(flag: bool, other: bool) -> bool:
	return other if flag else not other

func consume_selected(flag: bool, seed: int) -> int:
	return take(seed if flag else 0) + 1

func take(value: int) -> int:
	return value + 10

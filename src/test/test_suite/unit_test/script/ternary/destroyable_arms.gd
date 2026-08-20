class_name TernaryDestroyableArms
extends Node

func pick_string(flag: bool, left: String, right: String) -> String:
	return left if flag else right

func pick_array_size(flag: bool) -> int:
	var chosen: Array = [1, 2] if flag else [3, 4, 5]
	return chosen.size()

func pick_array_first(flag: bool) -> int:
	var chosen: Array = [10, 20] if flag else [30]
	return int(chosen[0])

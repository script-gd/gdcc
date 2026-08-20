class_name TernaryConditionContext
extends Node

func branch_on_int_arms(flag: bool, a: int, b: int) -> int:
	if a if flag else b:
		return 1
	return 0

func branch_on_bool_arms(flag: bool, a: bool, b: bool) -> int:
	if a if flag else b:
		return 10
	return 20

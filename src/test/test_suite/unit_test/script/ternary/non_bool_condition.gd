class_name TernaryNonBoolCondition
extends Node

func variant_condition(box: Variant, yes: int, no: int) -> int:
	return yes if box else no

func int_condition(count: int) -> int:
	return 100 if count else 200

func float_condition(mass: float) -> int:
	return 1 if mass else 0

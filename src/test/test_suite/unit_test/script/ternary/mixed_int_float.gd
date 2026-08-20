class_name TernaryMixedIntFloat
extends Node

func int_else_float(flag: bool, seed: int) -> float:
	return seed if flag else 2.5

func float_else_int(flag: bool, seed: int) -> float:
	return 1.5 if flag else seed

func variant_merge(flag: bool) -> Variant:
	return 7 if flag else "text"

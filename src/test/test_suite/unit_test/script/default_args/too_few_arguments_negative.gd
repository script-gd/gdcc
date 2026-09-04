class_name DefaultArgsTooFewNegative
extends Node

func need(a: int, b: int, c: int = 9) -> int:
	return a * 100 + b * 10 + c

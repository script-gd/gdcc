class_name TernaryNestedAssociativity
extends Node

func right_associative(c1: bool, c2: bool) -> int:
	return 1 if c1 else 2 if c2 else 3

func left_grouped(c1: bool, c2: bool) -> int:
	return (10 if c1 else 20) if c2 else 30

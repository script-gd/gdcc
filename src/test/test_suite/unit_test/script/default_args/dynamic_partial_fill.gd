class_name DefaultArgsDynamicPartialFill
extends Node

func greet(name: String, punct: String = "!", times: int = 2) -> String:
	var out: String = ""
	for i in range(times):
		out += name + punct
	return out

func mix(flag: bool, ratio: float = 2.0, add: int = 3) -> float:
	var base: float = ratio + float(add)
	if flag:
		return base * 10.0
	return base

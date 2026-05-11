class_name PropertyInitializerIntToFloatBoundaries
extends Node

var initial_ratio: float = 1
var assigned_ratio: float

func assign_from_int() -> float:
    assigned_ratio = 2
    return initial_ratio + assigned_ratio

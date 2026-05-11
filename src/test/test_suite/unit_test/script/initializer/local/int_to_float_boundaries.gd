class_name LocalInitializerIntToFloatBoundaries
extends Node

func take_float(v: float) -> float:
    return v

func return_int_as_float() -> float:
    return 4

func run() -> float:
    var ratio: float = 1
    ratio = 2
    return ratio + take_float(3) + return_int_as_float()

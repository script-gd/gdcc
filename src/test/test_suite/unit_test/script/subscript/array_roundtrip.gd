class_name ArrayRoundtripSmoke
extends Node

func compute(values: Array) -> int:
    values[1] = 6

    var first: int = values[0]
    var second: int = values[1]
    return first * 10 + second

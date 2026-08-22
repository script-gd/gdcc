class_name MatchArrayDestructureSmoke
extends Node

func describe(pair) -> int:
    match pair:
        [1, var x]:
            return x
        [var a, var b, ..]:
            return a + b
        _:
            return 0

func first_flag(arr: Array) -> int:
    match arr:
        [var head, ..] when head > 0:
            return head
        _:
            return -1

func compute() -> int:
    return describe([1, 41]) + describe([3, 4, 99]) + first_flag([7, 8]) + first_flag([])

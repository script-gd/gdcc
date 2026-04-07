class_name IfElifTruthinessSmoke
extends Node

func branch_value(value: int) -> int:
    if value > 0:
        return 100
    elif value:
        return 10
    else:
        return 1

func compute() -> int:
    return branch_value(2) + branch_value(-3) + branch_value(0)

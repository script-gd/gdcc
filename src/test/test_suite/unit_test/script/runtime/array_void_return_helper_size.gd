class_name ArrayVoidReturnHelperSizeSmoke
extends Node

func dynamic_size(value):
    return value.size()

func compute() -> int:
    var plain: Array = Array()
    plain.push_back(1)
    return dynamic_size(plain)

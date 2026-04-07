class_name DynamicCallRuntimeSmoke
extends Node

func dynamic_size(value):
    return value.size()

func compute() -> int:
    var values
    values = PackedInt32Array()
    values.push_back(4)
    values.push_back(5)
    values.push_back(6)

    var size: int = dynamic_size(values)
    return size

class_name PackedArrayMutationRoundtripSmoke
extends Node

func empty_size() -> int:
    var values: PackedInt32Array = PackedInt32Array()
    return values.size()

func compute() -> int:
    var values: PackedInt32Array = PackedInt32Array()
    values.push_back(4)
    values.push_back(5)
    values.push_back(6)
    return values[0] * 100 + values[1] * 10 + values[2]

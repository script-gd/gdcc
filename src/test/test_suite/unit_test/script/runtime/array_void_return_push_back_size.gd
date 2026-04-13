class_name ArrayVoidReturnPushBackSizeSmoke
extends Node

func compute() -> int:
    var plain: Array = Array()
    plain.push_back(1)
    return plain.size()

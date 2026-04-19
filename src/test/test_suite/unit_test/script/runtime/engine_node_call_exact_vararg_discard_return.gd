class_name EngineNodeCallExactVarargDiscardReturnSmoke
extends Node

func child_count_after_discarded_exact_call() -> int:
    var probe: Node = Node.new()
    probe.call(&"has_method", &"queue_free")
    return probe.get_child_count()

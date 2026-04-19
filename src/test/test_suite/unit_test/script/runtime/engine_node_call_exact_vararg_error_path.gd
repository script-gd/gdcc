class_name EngineNodeCallExactVarargErrorPathSmoke
extends Node

func child_count_after_missing_exact_call() -> int:
    var probe: Node = Node.new()
    probe.call(&"missing_method")
    return probe.get_child_count()

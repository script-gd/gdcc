class_name EngineNodeCallExactVarargSuccessSmoke
extends Node

func has_queue_free_via_exact_call() -> bool:
    var probe: Node = Node.new()
    var has_method: bool = probe.call(&"has_method", &"queue_free")
    return has_method

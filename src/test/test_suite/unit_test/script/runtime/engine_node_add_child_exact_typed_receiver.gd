class_name EngineNodeAddChildExactTypedReceiverSmoke
extends Node

func child_count_after_typed_add_child() -> int:
    var holder: Node = Node.new()
    var child: Node = Node.new()
    holder.add_child(child)
    return holder.get_child_count()

class_name EngineNodeAddChildExactExplicitInternalArgsSmoke
extends Node

func visible_plus_total_after_explicit_internal_add_child() -> int:
    var holder: Node = Node.new()
    var child: Node = Node.new()
    holder.add_child(child, false, 1)
    return holder.get_child_count() + holder.get_child_count(true)

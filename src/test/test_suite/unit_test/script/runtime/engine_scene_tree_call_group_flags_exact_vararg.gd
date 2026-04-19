class_name EngineSceneTreeCallGroupFlagsExactVarargSmoke
extends Node

func visible_plus_total_after_group_flags_internal_add_child() -> int:
    add_to_group(&"gdcc_exact_group_flags_smoke")
    var child: Node = Node.new()
    var tree: SceneTree = get_tree()
    tree.call_group_flags(0, &"gdcc_exact_group_flags_smoke", &"add_child", child, false, 1)
    return get_child_count() + get_child_count(true)

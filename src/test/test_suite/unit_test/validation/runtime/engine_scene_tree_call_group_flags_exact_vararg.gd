# gdcc-test: output_not_contains=engine method call failed: SceneTree.call_group_flags
extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var child_count = int(target.call("visible_plus_total_after_group_flags_internal_add_child"))
    if child_count == 1:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Engine SceneTree call_group_flags exact vararg validation failed: %s" % [child_count])

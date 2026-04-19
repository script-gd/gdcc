extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var child_count = int(target.call("child_count_after_typed_add_child"))
    if child_count == 1:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Engine Node exact typed add_child validation failed: %s" % [child_count])

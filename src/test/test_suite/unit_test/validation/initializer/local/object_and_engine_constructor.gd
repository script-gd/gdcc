extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    if target.call("null_object_value") != null:
        push_error("Null-to-Object local initializer validation failed.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

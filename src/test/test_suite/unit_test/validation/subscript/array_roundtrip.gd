extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    if int(target.call("compute", [4, 5])) == 46:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Array subscript validation failed.")

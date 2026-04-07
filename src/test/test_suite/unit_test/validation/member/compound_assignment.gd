extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var first = int(target.call("run_once"))
    var second = int(target.call("run_once"))
    if first == 12 and second == 82:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Compound assignment validation failed.")

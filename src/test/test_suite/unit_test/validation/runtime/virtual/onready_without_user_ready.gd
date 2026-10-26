extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    if int(target.call("probe")) != 17:
        push_error("Onready initializer did not run without a user _ready.")
        return
    print("__UNIT_TEST_PASS_MARKER__")

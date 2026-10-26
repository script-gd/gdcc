extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("create_child")
    target.call("store_value", 99)
    target.call("attach_child")
    if int(target.call("get_value")) != 5:
        push_error("Ready-time onready did not override the pre-ready stored value.")
        return
    print("__UNIT_TEST_PASS_MARKER__")

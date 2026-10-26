extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("create_child")
    target.call("child_call_manual")
    if int(target.call("get_apply_count")) != 1:
        push_error("First manual _ready did not apply the onready chain.")
        return
    if int(target.call("get_value")) != 41 or int(target.call("get_ready_calls")) != 1:
        push_error("First manual _ready did not apply the value or forward the body.")
        return

    target.call("child_call_manual")
    if int(target.call("get_apply_count")) != 1:
        push_error("DONE did not suppress the second manual _ready replay.")
        return
    if int(target.call("get_ready_calls")) != 2:
        push_error("Second manual _ready did not run the user body.")
        return

    target.call("attach_child")
    if int(target.call("get_apply_count")) != 2:
        push_error("Engine READY did not replay the chain through the composite impl.")
        return
    if int(target.call("get_ready_calls")) != 3:
        push_error("Engine READY did not forward the user _ready.")
        return
    print("__UNIT_TEST_PASS_MARKER__")

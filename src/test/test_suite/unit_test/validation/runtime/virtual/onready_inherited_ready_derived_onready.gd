extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("create_child")
    target.call("child_call_manual")
    var log: Array = target.call("get_log")
    if log.size() != 1 or log[0] != "sub":
        push_error("Inherited _ready did not dispatch to the subclass onready chain: " + str(log))
        return
    if int(target.call("get_value")) != 33 or int(target.call("get_ready_calls")) != 1:
        push_error("Inherited _ready did not apply the value or run the user body.")
        return

    target.call("attach_child")
    log = target.call("get_log")
    if log.size() != 2 or log[1] != "sub":
        push_error("Engine READY did not replay the subclass chain: " + str(log))
        return
    if int(target.call("get_ready_calls")) != 2:
        push_error("Engine READY did not forward the inherited user _ready.")
        return
    print("__UNIT_TEST_PASS_MARKER__")

extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("start_run")
    if bool(target.call("read_done")):
        push_error("Signal-returning nested await was incorrectly treated as passthrough.")
        return

    target.call("emit_value", 61)
    if not bool(target.call("read_done")) or int(target.call("read_result")) != 61:
        push_error("Signal-returning nested await did not resume through the coroutine chain.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

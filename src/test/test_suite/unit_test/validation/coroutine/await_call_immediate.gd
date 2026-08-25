extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("start_run")
    if not bool(target.call("read_done")) or int(target.call("read_result")) != 41:
        push_error("Completed coroutine state did not take the immediate fast path.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

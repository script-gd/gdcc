extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("start_wait", Signal())
    if not bool(target.call("read_done")) or target.call("read_result") != null:
        push_error("Signal connect failure suspended or produced a non-nil result.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

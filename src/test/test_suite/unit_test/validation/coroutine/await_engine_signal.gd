extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("start_wait")
    if int(target.call("read_phase")) != 1:
        push_error("Engine signal await did not suspend.")
        return

    await get_tree().process_frame
    if int(target.call("read_phase")) != 2:
        push_error("Engine process_frame signal did not resume the coroutine.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

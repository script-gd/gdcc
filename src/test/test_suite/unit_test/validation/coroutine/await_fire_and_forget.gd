extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("start_detached")
    if bool(target.call("read_done")):
        push_error("Detached coroutine completed before its signal.")
        return

    target.call("emit_release", 55)
    if not bool(target.call("read_done")) or int(target.call("read_result")) != 55:
        push_error("Fire-and-forget coroutine was not kept alive by its wait edge.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

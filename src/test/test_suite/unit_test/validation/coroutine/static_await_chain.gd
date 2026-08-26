extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("run")
    var waiting_events = target.call("read_events")
    if waiting_events != ["run:wait", "middle:wait", "leaf:wait"]:
        push_error("Static-to-static coroutine suspension order failed: %s" % [waiting_events])
        return

    target.call("emit_release", 10)
    var completed_events = target.call("read_events")
    if not bool(target.call("read_done")) or int(target.call("read_result")) != 13:
        push_error("Static-to-static coroutine result failed.")
        return
    if completed_events != ["run:wait", "middle:wait", "leaf:wait", "leaf:done", "middle:done", "run:done"]:
        push_error("Static-to-static coroutine resume order failed: %s" % [completed_events])
        return

    print("__UNIT_TEST_PASS_MARKER__")

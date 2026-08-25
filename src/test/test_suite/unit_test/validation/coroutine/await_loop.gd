extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("start_run")
    for index in range(3):
        target.call("emit_tick", (index + 1) * 10)
        if int(target.call("read_iteration")) != index + 1:
            push_error("Loop await did not advance exactly once at iteration %s." % index)
            return

    if not bool(target.call("read_done")) or int(target.call("read_total")) != 111:
        push_error("Loop await stack/local preservation failed: total=%s" % int(target.call("read_total")))
        return

    print("__UNIT_TEST_PASS_MARKER__")

extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("start_detached")
    if target.call("read_events") != []:
        push_error("Detached static coroutines ran before their signal.")
        return

    target.call("emit_release", 9)
    var events = target.call("read_events")
    if events != ["a:9", "b:9"]:
        push_error("Static fire-and-forget resume order failed: %s" % [events])
        return

    print("__UNIT_TEST_PASS_MARKER__")

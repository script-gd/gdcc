extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("start_run")
    if int(target.get("order")) != 123:
        push_error("named coroutine did not fire-and-forget the lambda coroutine synchronously: order=%s" % target.get("order"))
        return

    target.call("emit_tick")
    if int(target.get("order")) != 12345:
        push_error("shared-signal resume order broken between named and lambda coroutine: order=%s" % target.get("order"))
        return

    print("__UNIT_TEST_PASS_MARKER__")

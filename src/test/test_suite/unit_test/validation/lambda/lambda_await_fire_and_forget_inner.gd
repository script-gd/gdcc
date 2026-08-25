extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var cb: Callable = target.call("make_cb")
    var state = cb.call()
    if state == null:
        push_error("coroutine lambda call did not return a state.")
        return
    if int(target.get("order")) != 123:
        push_error("fire-and-forget inner coroutine did not start synchronously: order=%s" % target.get("order"))
        return

    target.call("emit_tick")
    if int(target.get("order")) != 12356:
        push_error("resume order broken for fire-and-forget inner coroutine: order=%s" % target.get("order"))
        return

    print("__UNIT_TEST_PASS_MARKER__")

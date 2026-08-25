extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var cb: Callable = target.call("make_self_cb")
    if cb.get_object() != target or not cb.is_valid():
        push_error("self-capturing coroutine lambda lost its object identity.")
        return
    var state = cb.call()
    if state == null:
        push_error("coroutine lambda call did not return a state.")
        return
    if int(target.get("flag")) != 0:
        push_error("coroutine lambda did not suspend before emit.")
        return

    target.call("emit_tick")
    if int(target.get("flag")) != 7:
        push_error("coroutine lambda did not resume through the captured self: flag=%s" % target.get("flag"))
        return

    print("__UNIT_TEST_PASS_MARKER__")

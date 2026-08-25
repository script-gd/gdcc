extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var cb: Callable = target.call("make_cb")
    var direct = cb.call(3)
    if typeof(direct) != TYPE_INT or int(direct) != 8:
        push_error("done fast path must return the lambda value directly, got: %s" % [direct])
        return

    var state = cb.call(-1)
    if state == null or typeof(state) != TYPE_OBJECT:
        push_error("suspend path must return the coroutine state object.")
        return
    if int(target.get("result")) != -1:
        push_error("suspend path did not suspend before emit.")
        return

    target.call("emit_value", 9)
    if int(target.get("result")) != 9:
        push_error("suspend path did not resume with the emitted value.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

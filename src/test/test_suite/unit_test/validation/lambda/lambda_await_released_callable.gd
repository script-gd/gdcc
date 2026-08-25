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

    cb = Callable()
    state = null
    target.call("emit_release")
    if int(target.get("result")) != 41:
        push_error("suspended lambda did not keep running after the Callable was released: result=%s" % target.get("result"))
        return

    print("__UNIT_TEST_PASS_MARKER__")

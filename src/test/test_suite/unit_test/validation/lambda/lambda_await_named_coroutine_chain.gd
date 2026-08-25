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
    if not bool(target.get("suspended")) or int(target.get("result")) != -1:
        push_error("lambda did not run to its await of the named coroutine.")
        return

    target.call("emit_value", 5)
    if bool(target.get("suspended")):
        push_error("lambda did not resume after the named coroutine chain completed.")
        return
    if int(target.get("result")) != 112:
        push_error("typed result did not cross leaf -> step -> lambda: result=%s" % target.get("result"))
        return

    print("__UNIT_TEST_PASS_MARKER__")

extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var cb: Callable = target.call("make_cb")
    var state = cb.call()
    if state == null:
        push_error("Coroutine lambda call did not return a state.")
        return
    if not bool(target.get("suspended")) or int(target.get("result")) != -1:
        push_error("Lambda did not run to its await of the static coroutine.")
        return

    target.call("emit_release", 4)
    if bool(target.get("suspended")):
        push_error("Lambda did not resume after the static coroutine completed.")
        return
    if int(target.get("result")) != 50:
        push_error("Typed result did not cross static coroutine -> lambda: result=%s" % target.get("result"))
        return

    print("__UNIT_TEST_PASS_MARKER__")

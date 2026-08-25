extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var cb: Callable = target.call("make_outer")
    var state = cb.call()
    if state == null:
        push_error("coroutine lambda call did not return a state.")
        return
    if int(target.get("order")) != 12:
        push_error("nested coroutine lambda did not start and suspend correctly: order=%s" % target.get("order"))
        return

    target.call("emit_value", 7)
    if int(target.get("order")) != 1234:
        push_error("nested coroutine lambda resume order broken: order=%s" % target.get("order"))
        return

    print("__UNIT_TEST_PASS_MARKER__")

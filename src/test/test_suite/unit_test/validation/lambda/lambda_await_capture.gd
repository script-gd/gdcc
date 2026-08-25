extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var cb: Callable = target.call("make_cb", 7, "cap")
    var state = cb.call()
    if state == null:
        push_error("coroutine lambda call did not return a state.")
        return
    if int(target.get("label_seed")) != -1 or String(target.get("label_prefix")) != "":
        push_error("coroutine lambda did not suspend before emit.")
        return

    target.call("emit_value", 21)
    if String(target.get("label_prefix")) != "cap" or int(target.get("label_seed")) != 7 or int(target.get("label_value")) != 21:
        push_error("coroutine lambda did not resume with captured values.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

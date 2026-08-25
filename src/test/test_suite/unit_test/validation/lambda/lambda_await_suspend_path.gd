extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var cb: Callable = target.call("make_cb")
    var state = cb.call()
    if state == null or typeof(state) != TYPE_OBJECT:
        push_error("suspended coroutine lambda must return its state object through the Callable ABI.")
        return
    if int(target.get("result")) != -1:
        push_error("coroutine lambda did not suspend before emit.")
        return

    target.call("emit_value", 9)
    if int(target.get("result")) != 9:
        push_error("coroutine lambda did not resume with the emitted value.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

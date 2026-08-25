extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var cb: Callable = target.call("make_cb")
    var state_a = cb.call()
    var state_b = cb.call()
    if state_a == null or state_b == null or state_a == state_b:
        push_error("two calls did not produce two distinct suspended states.")
        return
    if int(target.get("first_seen")) != 1 or int(target.get("second_seen")) != 1:
        push_error("each call must copy captures into its own frame: first=%s second=%s" % [target.get("first_seen"), target.get("second_seen")])
        return
    if int(target.get("done_count")) != 0:
        push_error("coroutine lambdas did not suspend before emit.")
        return

    target.call("emit_tick")
    if int(target.get("done_count")) != 2:
        push_error("coroutine lambdas did not both resume after emit.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

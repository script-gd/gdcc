# gdcc-test: output_not_contains=engine method call failed: Node.add_child
# gdcc-test: output_not_contains=engine method call failed: Node.get_node
extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var cb: Callable = target.call("make_await_cb")
    if cb.get_object() != target or not cb.is_valid():
        push_error("Get-node coroutine lambda lost its object identity.")
        return
    var state = cb.call()
    if state == null:
        push_error("Get-node coroutine lambda call did not return a state.")
        return
    if int(target.get("phase")) != 1 or String(target.get("pre_name")) != "Child":
        push_error("Coroutine lambda did not resolve the plain child before suspension.")
        return

    target.call("emit_tick")
    if int(target.get("phase")) != 2 or String(target.get("post_name")) != "Unique":
        push_error("Coroutine lambda did not resolve the unique-name node after resume.")
        return

    target.call("connect_unique_cb")
    target.call("emit_tick")
    if String(target.get("connected_name")) != "Unique":
        push_error("Signal-connect callback lambda did not resolve the unique-name node.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

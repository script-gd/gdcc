extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var dynamic_signal = target.get("dynamic_tick")
    target.call("start_run", dynamic_signal)
    if bool(target.call("read_done")):
        push_error("Nested dynamic signal await completed before emit.")
        return

    target.call("emit_tick", 37)
    if not bool(target.call("read_done")) or int(target.call("read_result")) != 37:
        push_error("Nested dynamic signal await did not propagate its result.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

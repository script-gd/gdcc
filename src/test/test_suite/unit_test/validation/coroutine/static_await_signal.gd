extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("run")
    if bool(target.call("read_done")):
        push_error("Static coroutine did not suspend on the other object's signal.")
        return

    target.call("emit_pulse", 7)
    if not bool(target.call("read_done")) or int(target.call("read_result")) != 21:
        push_error("Static coroutine awaiting another object's signal resumed with a wrong result.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

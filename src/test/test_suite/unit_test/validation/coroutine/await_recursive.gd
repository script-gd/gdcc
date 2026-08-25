extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("start_run", 4)
    if bool(target.call("read_done")):
        push_error("Recursive await completed before the base signal.")
        return

    target.call("emit_base", 10)
    if not bool(target.call("read_done")) or int(target.call("read_result")) != 20:
        push_error("Recursive await chain produced the wrong result.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

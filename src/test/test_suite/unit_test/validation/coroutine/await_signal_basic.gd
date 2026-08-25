extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("start_wait")
    if int(target.call("read_phase")) != 1 or int(target.call("read_result")) != -1:
        push_error("Signal await did not suspend before emit.")
        return

    target.call("emit_value", 17)
    if int(target.call("read_phase")) != 2 or int(target.call("read_result")) != 17:
        push_error("Signal await did not resume with the emitted value.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

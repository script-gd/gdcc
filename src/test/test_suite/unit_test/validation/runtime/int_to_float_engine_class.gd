extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var result = float(target.call("timer_wait_time_after_int_assignment"))
    if result == 2.0:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Engine class int-to-float validation failed: result=%s" % result)

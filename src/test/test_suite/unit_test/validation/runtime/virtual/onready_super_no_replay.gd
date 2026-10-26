extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("run_case")
    if int(target.call("get_apply_count")) != 1:
        push_error("super._ready() replayed the parent onready chain.")
        return
    if int(target.call("get_value")) != 7:
        push_error("Parent onready value missing after engine READY.")
        return
    if int(target.call("get_child_ready_calls")) != 1:
        push_error("Child _ready did not run exactly once.")
        return
    if int(target.call("get_parent_ready_calls")) != 1:
        push_error("Parent _ready body did not run exactly once via super.")
        return
    print("__UNIT_TEST_PASS_MARKER__")

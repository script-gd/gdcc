extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var ok := true
    if int(target.call("for_continue_break")) != 6:
        push_error("for_continue_break: expected 6, got %d" % int(target.call("for_continue_break")))
        ok = false
    if int(target.call("while_continue_break")) != 8:
        push_error("while_continue_break: expected 8, got %d" % int(target.call("while_continue_break")))
        ok = false
    if int(target.call("body_for")) != 4:
        push_error("body_for: expected 4, got %d" % int(target.call("body_for")))
        ok = false
    if int(target.call("body_if")) != 7:
        push_error("body_if: expected 7, got %d" % int(target.call("body_if")))
        ok = false
    if int(target.call("body_fallback")) != -1:
        push_error("body_fallback: expected -1, got %d" % int(target.call("body_fallback")))
        ok = false
    if int(target.call("nested_hit")) != 12:
        push_error("nested_hit: expected 12, got %d" % int(target.call("nested_hit")))
        ok = false
    if int(target.call("nested_inner_fallback")) != 10:
        push_error("nested_inner_fallback: expected 10, got %d" % int(target.call("nested_inner_fallback")))
        ok = false
    if int(target.call("nested_outer_fallback")) != 0:
        push_error("nested_outer_fallback: expected 0, got %d" % int(target.call("nested_outer_fallback")))
        ok = false
    if int(target.call("if_true_hit")) != 1:
        push_error("if_true_hit: expected 1, got %d" % int(target.call("if_true_hit")))
        ok = false
    if int(target.call("if_true_fallback")) != 2:
        push_error("if_true_fallback: expected 2, got %d" % int(target.call("if_true_fallback")))
        ok = false
    if int(target.call("if_false")) != 0:
        push_error("if_false: expected 0, got %d" % int(target.call("if_false")))
        ok = false

    if ok:
        print("__UNIT_TEST_PASS_MARKER__")

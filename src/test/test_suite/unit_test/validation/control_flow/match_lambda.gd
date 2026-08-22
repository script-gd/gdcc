extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var ok := true
    if int(target.call("bind_capture_hit")) != 6:
        push_error("bind_capture_hit: expected 6, got %d" % int(target.call("bind_capture_hit")))
        ok = false
    if int(target.call("inside_lambda_one")) != 10:
        push_error("inside_lambda_one: expected 10, got %d" % int(target.call("inside_lambda_one")))
        ok = false
    if int(target.call("inside_lambda_or")) != 20:
        push_error("inside_lambda_or: expected 20, got %d" % int(target.call("inside_lambda_or")))
        ok = false
    if int(target.call("inside_lambda_wildcard")) != 1:
        push_error("inside_lambda_wildcard: expected 1, got %d" % int(target.call("inside_lambda_wildcard")))
        ok = false
    if int(target.call("nested_bind_capture_hit")) != 11:
        push_error("nested_bind_capture_hit: expected 11, got %d" % int(target.call("nested_bind_capture_hit")))
        ok = false
    if int(target.call("nested_bind_capture_miss")) != 0:
        push_error("nested_bind_capture_miss: expected 0, got %d" % int(target.call("nested_bind_capture_miss")))
        ok = false

    if ok:
        print("__UNIT_TEST_PASS_MARKER__")

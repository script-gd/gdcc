extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var ok := true
    if int(target.call("inner_qualified")) != 12:
        push_error("inner_qualified failed")
        ok = false
    if int(target.call("match_key", 8)) != 1:
        push_error("match_key hit failed")
        ok = false
    if int(target.call("match_key", 1)) != -1:
        push_error("match_key miss failed")
        ok = false
    if int(target.call("with_default")) != 8:
        push_error("with_default omitted failed")
        ok = false
    if int(target.call("with_default", 3)) != 3:
        push_error("with_default explicit failed")
        ok = false
    if int(target.call("lambda_reader")) != 42:
        push_error("lambda_reader failed")
        ok = false
    if int(ClassDB.class_call_static("ConstUsageContexts", "static_limit")) != 9:
        push_error("static_limit failed")
        ok = false

    if ok:
        print("__UNIT_TEST_PASS_MARKER__")

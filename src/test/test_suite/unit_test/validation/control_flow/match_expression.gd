extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var ok := true
    if int(target.call("identifier_hit")) != 1:
        push_error("identifier_hit: expected 1, got %d" % int(target.call("identifier_hit")))
        ok = false
    if int(target.call("identifier_miss")) != 0:
        push_error("identifier_miss: expected 0, got %d" % int(target.call("identifier_miss")))
        ok = false
    if int(target.call("call_hit")) != 1:
        push_error("call_hit: expected 1, got %d" % int(target.call("call_hit")))
        ok = false
    if int(target.call("call_plus_one_hit")) != 2:
        push_error("call_plus_one_hit: expected 2, got %d" % int(target.call("call_plus_one_hit")))
        ok = false
    if int(target.call("call_miss")) != 0:
        push_error("call_miss: expected 0, got %d" % int(target.call("call_miss")))
        ok = false
    if int(target.call("typeof_int")) != 1:
        push_error("typeof_int: expected 1, got %d" % int(target.call("typeof_int")))
        ok = false
    if int(target.call("typeof_float")) != 2:
        push_error("typeof_float: expected 2, got %d" % int(target.call("typeof_float")))
        ok = false
    if int(target.call("typeof_string")) != 3:
        push_error("typeof_string: expected 3, got %d" % int(target.call("typeof_string")))
        ok = false
    if int(target.call("typeof_array")) != 4:
        push_error("typeof_array: expected 4, got %d" % int(target.call("typeof_array")))
        ok = false
    if int(target.call("typeof_other")) != 0:
        push_error("typeof_other: expected 0, got %d" % int(target.call("typeof_other")))
        ok = false

    if ok:
        print("__UNIT_TEST_PASS_MARKER__")

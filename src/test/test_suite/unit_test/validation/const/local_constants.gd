extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var ok := true
    if int(target.call("local_chain")) != 15:
        push_error("local_chain failed")
        ok = false
    if int(target.call("outer_fallback")) != 11:
        push_error("outer_fallback failed")
        ok = false
    if int(target.call("nested_scopes")) != 11:
        push_error("nested_scopes failed")
        ok = false
    if int(target.call("local_shadow")) != 99:
        push_error("local_shadow failed")
        ok = false

    if ok:
        print("__UNIT_TEST_PASS_MARKER__")

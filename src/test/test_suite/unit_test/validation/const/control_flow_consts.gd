extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var ok := true
    var result = target.call("branch_values", 11)
    if typeof(result) != TYPE_INT or result != 20:
        push_error("branch_values if failed")
        ok = false
    result = target.call("branch_values", 10)
    if typeof(result) != TYPE_INT or result != 13:
        push_error("branch_values elif failed")
        ok = false
    result = target.call("branch_values", 5)
    if typeof(result) != TYPE_INT or result != 3:
        push_error("branch_values else failed")
        ok = false
    result = target.call("loop_sum")
    if typeof(result) != TYPE_INT or result != 129:
        push_error("loop_sum failed")
        ok = false
    result = target.call("countdown")
    if typeof(result) != TYPE_INT or result != 6:
        push_error("countdown failed")
        ok = false
    result = target.call("match_nested", 10)
    if typeof(result) != TYPE_INT or result != 1:
        push_error("match_nested hit failed")
        ok = false
    result = target.call("match_nested", 4)
    if typeof(result) != TYPE_INT or result != 3:
        push_error("match_nested nested even failed")
        ok = false
    result = target.call("match_nested", 5)
    if typeof(result) != TYPE_INT or result != 0:
        push_error("match_nested nested odd failed")
        ok = false
    result = target.call("nested_shadow")
    if typeof(result) != TYPE_INT or result != 442:
        push_error("nested_shadow failed")
        ok = false
    result = target.call("early_exit", -3)
    if typeof(result) != TYPE_INT or result != -7:
        push_error("early_exit negative failed")
        ok = false
    result = target.call("early_exit", 0)
    if typeof(result) != TYPE_INT or result != 0:
        push_error("early_exit zero failed")
        ok = false
    result = target.call("early_exit", 5)
    if typeof(result) != TYPE_INT or result != 19:
        push_error("early_exit positive failed")
        ok = false
    result = target.call("sign_of", 1)
    if typeof(result) != TYPE_INT or result != 10:
        push_error("sign_of positive failed")
        ok = false
    result = target.call("sign_of", -1)
    if typeof(result) != TYPE_INT or result != 3:
        push_error("sign_of negative failed")
        ok = false

    if ok:
        print("__UNIT_TEST_PASS_MARKER__")

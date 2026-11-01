extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var ok := true
    var result = target.call("inner_chain")
    if typeof(result) != TYPE_INT or result != 50:
        push_error("inner_chain failed")
        ok = false
    var inner_pi := float(target.call("inner_pi"))
    if not is_finite(inner_pi) or not is_equal_approx(inner_pi, PI):
        push_error("inner_pi failed")
        ok = false
    if target.call("inner_builtin") != Vector2(3.0, 4.0):
        push_error("inner_builtin failed")
        ok = false
    result = target.call("inner_in_local")
    if typeof(result) != TYPE_INT or result != 14:
        push_error("inner_in_local failed")
        ok = false
    result = target.call("inner_match_key", 7)
    if typeof(result) != TYPE_INT or result != 1:
        push_error("inner_match_key hit failed")
        ok = false
    result = target.call("inner_match_key", 1)
    if typeof(result) != TYPE_INT or result != -1:
        push_error("inner_match_key miss failed")
        ok = false
    result = target.call("inner_default")
    if typeof(result) != TYPE_INT or result != 7:
        push_error("inner_default omitted failed")
        ok = false
    result = target.call("inner_default", 3)
    if typeof(result) != TYPE_INT or result != 3:
        push_error("inner_default explicit failed")
        ok = false
    result = target.call("sibling_read")
    if typeof(result) != TYPE_INT or result != 12:
        push_error("sibling_read failed")
        ok = false
    result = target.call("deep_read")
    if typeof(result) != TYPE_INT or result != 9:
        push_error("deep_read failed")
        ok = false

    if ok:
        print("__UNIT_TEST_PASS_MARKER__")

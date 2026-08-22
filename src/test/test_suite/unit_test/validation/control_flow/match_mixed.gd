extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var ok := true
    if int(target.call("for_match_dict_lambda")) != 10:
        push_error("for_match_dict_lambda: expected 10, got %d" % int(target.call("for_match_dict_lambda")))
        ok = false
    if int(target.call("while_match_array_if")) != 10:
        push_error("while_match_array_if: expected 10, got %d" % int(target.call("while_match_array_if")))
        ok = false
    if int(target.call("string_match_in_for")) != 12:
        push_error("string_match_in_for: expected 12, got %d" % int(target.call("string_match_in_for")))
        ok = false

    if ok:
        print("__UNIT_TEST_PASS_MARKER__")

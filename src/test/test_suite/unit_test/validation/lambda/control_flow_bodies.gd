extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var ctor_value = int(target.call("constructor_capture"))
    var if_true = int(target.call("in_if_true"))
    var if_false = int(target.call("in_if_false"))
    var while_total = int(target.call("in_while"))
    var for_body = int(target.call("in_for_body"))
    var for_iter = int(target.call("in_for_capture_iterator"))

    if ctor_value != 41:
        push_error("constructor-body lambda failed: %s" % ctor_value)
        return
    if if_true != 1:
        push_error("if-true lambda failed: %s" % if_true)
        return
    if if_false != 2:
        push_error("if-false lambda failed: %s" % if_false)
        return
    if while_total != 3:
        push_error("while-body lambda failed: %s" % while_total)
        return
    if for_body != 12:
        push_error("for-body lambda failed: %s" % for_body)
        return
    if for_iter != 6:
        push_error("for-iterator capture in loop body failed: %s" % for_iter)
        return

    print("__UNIT_TEST_PASS_MARKER__")

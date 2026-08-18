extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var member_hits = int(target.call("implicit_self_member"))
    var method_hits = int(target.call("implicit_self_method"))
    var explicit_hits = int(target.call("explicit_self"))
    var object_is_self = bool(target.call("self_get_object_is_target"))
    var returned = int(target.call("call_returned", 6))
    var passed = int(target.call("pass_as_argument", 5))
    var nested = int(target.call("nested_capture"))

    if member_hits != 1:
        push_error("implicit self member write failed: %s" % member_hits)
        return
    if method_hits != 1:
        push_error("implicit self method call failed: %s" % method_hits)
        return
    if explicit_hits != 4:
        push_error("explicit self capture failed: %s" % explicit_hits)
        return
    if not object_is_self:
        push_error("self-capturing lambda get_object should be the instance")
        return
    if returned != 7:
        push_error("returned lambda call failed: %s" % returned)
        return
    if passed != 15:
        push_error("lambda Callable argument failed: %s" % passed)
        return
    if nested != 42:
        push_error("nested lambda capture transfer failed: %s" % nested)
        return

    print("__UNIT_TEST_PASS_MARKER__")

extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var constant_value = int(target.call("captureless_constant"))
    var arity = int(target.call("captureless_argument_count"))
    var captured_arity = int(target.call("captured_argument_count"))
    var typed_arity = int(target.call("typed_param_argument_count"))
    var typed_value = int(target.call("typed_param_and_return", 4))
    var omitted_value = int(target.call("omitted_return_type", 4))
    var echoed = target.call("untyped_param", "ok")
    var stored_value = int(target.call("stored_member_call"))
    var valid = bool(target.call("captureless_is_valid"))
    var object_is_null = bool(target.call("captureless_get_object_is_null"))
    var distinct = bool(target.call("distinct_identity"))

    if constant_value != 7:
        push_error("captureless constant failed: %s" % constant_value)
        return
    if arity != 0:
        push_error("captureless arity failed: %s" % arity)
        return
    if captured_arity != 0:
        push_error("captured lambda arity must exclude captures: %s" % captured_arity)
        return
    if typed_arity != 1:
        push_error("typed-param arity failed: %s" % typed_arity)
        return
    if typed_value != 5:
        push_error("typed param/return failed: %s" % typed_value)
        return
    if omitted_value != 8:
        push_error("omitted return type failed: %s" % omitted_value)
        return
    if String(echoed) != "ok":
        push_error("untyped param echo failed: %s" % echoed)
        return
    if stored_value != 8:
        push_error("stored member lambda failed: %s" % stored_value)
        return
    if not valid:
        push_error("captureless is_valid expected true")
        return
    if not object_is_null:
        push_error("captureless get_object expected null")
        return
    if not distinct:
        push_error("separately constructed lambdas should not compare equal")
        return

    print("__UNIT_TEST_PASS_MARKER__")

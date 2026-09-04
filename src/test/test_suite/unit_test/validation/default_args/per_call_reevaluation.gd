extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    # A default expression that mutates instance state must re-evaluate on every call.
    var first = int(target.call("take"))
    if first != 1:
        push_error("first take() expected 1, got %d" % first)
        return

    var second = int(target.call("take"))
    if second != 2:
        push_error("second take() expected 2, got %d" % second)
        return

    var explicit_arg = int(target.call("take", 9))
    if explicit_arg != 9:
        push_error("take(9) expected 9, got %d" % explicit_arg)
        return

    # The explicit call above must not evaluate the default expression.
    var third = int(target.call("take"))
    if third != 3:
        push_error("third take() expected 3, got %d" % third)
        return

    # Mutable container defaults are constructed fresh per call, never shared.
    var probe_first = int(target.call("append_probe"))
    if probe_first != 20:
        push_error("first append_probe() expected 20, got %d" % probe_first)
        return

    var probe_second = int(target.call("append_probe"))
    if probe_second != 20:
        push_error("second append_probe() expected 20 (fresh default array), got %d" % probe_second)
        return

    print("__UNIT_TEST_PASS_MARKER__")

extends Node

func _is_close(actual: float, expected: float) -> bool:
    return abs(actual - expected) < 0.0001

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var exact_int = int(target.call("exact_int_reopen"))
    if exact_int != 42:
        push_error("Atomic builtin constructor int(int) validation failed: %d" % exact_int)
        return

    var bool_mask = int(target.call("bool_from_int_mask"))
    if bool_mask != 3:
        push_error("Atomic builtin constructor bool(int) validation failed: %d" % bool_mask)
        return

    var bool_delta = int(target.call("int_from_bool_delta"))
    if bool_delta != 1:
        push_error("Atomic builtin constructor int(bool) validation failed: %d" % bool_delta)
        return

    var float_mix = float(target.call("float_bool_int_mix"))
    if not _is_close(float_mix, 6.0):
        push_error("Atomic builtin constructor float(bool/int) validation failed: %s" % float_mix)
        return

    var truncation = int(target.call("int_from_float_truncation"))
    if truncation != 6:
        push_error("Atomic builtin constructor int(float) validation failed: %d" % truncation)
        return

    print("__UNIT_TEST_PASS_MARKER__")

extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    # Dynamic Object.call path: the callee wrapper fills omitted trailing defaults.
    var both_default = str(target.call("greet", "hi"))
    if both_default != "hi!hi!":
        push_error("greet with both defaults failed: %s" % both_default)
        return

    var second_default = str(target.call("greet", "hi", "?"))
    if second_default != "hi?hi?":
        push_error("greet with tail default failed: %s" % second_default)
        return

    var none_default = str(target.call("greet", "hi", "?", 1))
    if none_default != "hi?":
        push_error("greet fully provided failed: %s" % none_default)
        return

    # Heterogeneous bool/float/int defaults with progressive omission.
    var mix_all_default = float(target.call("mix", true))
    if abs(mix_all_default - 50.0) > 0.0001:
        push_error("mix with both defaults failed: %s" % mix_all_default)
        return

    var mix_tail_default = float(target.call("mix", false, 1.5))
    if abs(mix_tail_default - 4.5) > 0.0001:
        push_error("mix with tail default failed: %s" % mix_tail_default)
        return

    var mix_full = float(target.call("mix", true, 1.0, 1))
    if abs(mix_full - 20.0) > 0.0001:
        push_error("mix fully provided failed: %s" % mix_full)
        return

    # Variant receiver dynamic dispatch must reach the same argc-aware wrapper.
    var via_variant: Variant = target
    var variant_result = via_variant.greet("ok")
    if variant_result != "ok!ok!":
        push_error("greet through Variant receiver failed: %s" % variant_result)
        return

    print("__UNIT_TEST_PASS_MARKER__")

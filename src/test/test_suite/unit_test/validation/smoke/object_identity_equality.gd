extends Node

const EXPECTED_MASK := 127

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var mask = int(target.call("identity_mask"))
    if mask == EXPECTED_MASK:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("ObjectIdentityEqualitySmoke validation failed: mask=%s, expected=%s" % [mask, EXPECTED_MASK])

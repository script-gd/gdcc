extends Node

const EXPECTED_MASK := 255

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var mask = int(target.call("membership_mask"))
    if mask == EXPECTED_MASK:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("NotInMembershipSmoke validation failed: mask=%s, expected=%s" % [mask, EXPECTED_MASK])

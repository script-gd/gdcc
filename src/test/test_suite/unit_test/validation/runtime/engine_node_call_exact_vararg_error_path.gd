# gdcc-test: output_contains=engine method call failed: Object.call
extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var child_count = int(target.call("child_count_after_missing_exact_call"))
    if child_count == 0:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Engine Node exact vararg error-path validation failed: %s" % [child_count])

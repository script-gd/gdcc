# gdcc-test: output_not_contains=engine method call failed: Object.call
extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var has_method = bool(target.call("has_queue_free_via_exact_call"))
    if has_method:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Engine Node exact vararg success validation failed.")

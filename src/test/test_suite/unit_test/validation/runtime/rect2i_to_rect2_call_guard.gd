# gdcc-test: output_contains=Cannot convert argument 2 from Rect2i to Rect2.
# gdcc-test: output_not_contains=rect2i-to-rect2 guard after bad call.
extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    print("__UNIT_TEST_PASS_MARKER__")
    target.call("take_rect2", Rect2i(1, 2, 3, 4))
    print("rect2i-to-rect2 guard after bad call.")

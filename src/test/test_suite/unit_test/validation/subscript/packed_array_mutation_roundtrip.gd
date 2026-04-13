extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    if int(target.call("empty_size")) != 0:
        push_error("Packed array empty control validation failed.")
        return

    if int(target.call("compute")) != 456:
        push_error("Packed array mutation roundtrip validation failed.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

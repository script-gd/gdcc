extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var parsed = int(target.call("parse_count", "{\"n\": 7}"))
    if parsed != 7:
        push_error("Engine static direct call JSON.parse_string failed: %s" % parsed)
        return

    print("__UNIT_TEST_PASS_MARKER__")

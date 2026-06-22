# gdcc-test: output_contains_any=variant_get_named failed || Invalid get index || Invalid set index
extends Node

class DynamicMemberHost extends RefCounted:
    var marker: String = "known"

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var missing = target.call("read_missing", DynamicMemberHost.new())
    if missing == null:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Dynamic member missing-name access validation failed.")

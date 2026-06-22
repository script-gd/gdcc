extends Node

class DynamicMemberHost extends RefCounted:
    var marker: String = "before"

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var host = DynamicMemberHost.new()
    var initial = target.call("read_marker", host)
    target.call("write_marker", host, "after")
    var updated = target.call("read_marker", host)

    if String(initial) == "before" and String(updated) == "after" and host.marker == "after":
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Dynamic member Variant named access validation failed.")

extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var scores: Dictionary[float, int] = {}
    var payloads: Dictionary[float, PackedInt32Array] = {}
    var direct = int(target.call("write_and_read", scores, 2))
    var nested = int(target.call("nested_writeback", payloads, 3, 11))
    if direct == 7 and nested == 111:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Dictionary float-key subscript validation failed: direct=%d nested=%d" % [direct, nested])

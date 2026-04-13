extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var result = float(target.call("vector_x_after_write"))
    if abs(result - 5.0) > 0.0001:
        push_error("Builtin Vector3 property writeback validation failed: %s" % [result])
        return

    print("__UNIT_TEST_PASS_MARKER__")

extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var vector_result = float(target.call("vector_y"))
    if abs(vector_result - 2.0) > 0.0001:
        push_error("Builtin Vector3 property read validation failed: %s" % [vector_result])
        return

    var color_result = float(target.call("color_r"))
    if abs(color_result - 0.25) > 0.0001:
        push_error("Builtin Color property read validation failed: %s" % [color_result])
        return

    print("__UNIT_TEST_PASS_MARKER__")

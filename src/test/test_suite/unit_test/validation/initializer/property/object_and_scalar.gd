extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    if int(target.call("read_value")) != 15:
        push_error("Property initializer scalar validation failed.")
        return

    if abs(float(target.call("read_axis_y")) - 2.0) > 0.0001:
        push_error("Property initializer builtin validation failed.")
        return

    if String(target.call("read_node_class")) != "Node":
        push_error("Property initializer Node object validation failed.")
        return

    if String(target.call("read_object_class")) != "Node":
        push_error("Property initializer Object route validation failed.")
        return

    if int(target.call("read_ref_count")) < 1:
        push_error("Property initializer RefCounted validation failed.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

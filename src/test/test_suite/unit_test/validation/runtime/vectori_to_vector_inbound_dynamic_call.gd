extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var vector2_sum = float(target.call("take_vector2", Vector2i(1, 2)))
    var vector3_sum = float(target.call("take_vector3", Vector3i(1, 2, 3)))
    var vector4_sum = float(target.call("take_vector4", Vector4i(1, 2, 3, 4)))
    var exact_vector3_y = float(target.call("vector3_y", Vector3(7.5, 8.5, 9.5)))

    if is_equal_approx(vector2_sum, 3.0) \
            and is_equal_approx(vector3_sum, 6.0) \
            and is_equal_approx(vector4_sum, 10.0) \
            and is_equal_approx(exact_vector3_y, 8.5):
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Inbound Vector*i-to-Vector dynamic call validation failed.")

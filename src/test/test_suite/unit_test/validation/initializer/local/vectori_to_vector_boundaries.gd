extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var local_sum = float(target.call("local_sum"))
    var assignment_sum = float(target.call("assignment_sum", Vector3i(4, 5, 6)))
    var fixed_arg_sum = float(target.call("fixed_arg_sum", Vector3i(7, 8, 9)))
    var returned = target.call("return_vector", Vector3i(10, 11, 12))

    if is_equal_approx(local_sum, 6.0) \
            and is_equal_approx(assignment_sum, 15.0) \
            and is_equal_approx(fixed_arg_sum, 24.0) \
            and typeof(returned) == TYPE_VECTOR3 \
            and returned == Vector3(10.0, 11.0, 12.0):
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Vector*i-to-Vector source boundary validation failed.")

extends Node

func _is_close(actual: float, expected: float) -> bool:
    return abs(actual - expected) < 0.0001

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var checks := [
        ["vector2_mixed_sum", 8.5],
        ["vector3_mixed_sum", 6.25],
        ["vector4_mixed_sum", 11.75],
        ["color_mixed_sum", 3.625],
        ["rect2_mixed_size_area", 13.5],
        ["plane_mixed_sum", 5.75],
        ["quaternion_mixed_sum", 2.75],
    ]
    for check in checks:
        var method_name = String(check[0])
        var expected = float(check[1])
        var result = float(target.call(method_name))
        if not _is_close(result, expected):
            push_error(
                "Builtin constructor int-to-float validation failed: %s result=%s expected=%s"
                % [method_name, result, expected]
            )
            return

    print("__UNIT_TEST_PASS_MARKER__")

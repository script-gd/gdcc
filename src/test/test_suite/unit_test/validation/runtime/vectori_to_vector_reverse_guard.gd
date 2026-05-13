# gdcc-test: output_contains=Cannot convert argument 2 from Vector3 to Vector3i.
# gdcc-test: output_not_contains=vectori-to-vector reverse guard after bad call.
extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    print("__UNIT_TEST_PASS_MARKER__")
    target.call("take_vector3i", Vector3(1.0, 2.0, 3.0))
    print("vectori-to-vector reverse guard after bad call.")

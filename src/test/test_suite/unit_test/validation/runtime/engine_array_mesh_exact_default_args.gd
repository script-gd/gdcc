extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var surface_count = int(target.call("surface_count_after_exact_default_args"))
    if surface_count == 1:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Engine ArrayMesh exact default-argument validation failed: %s" % [surface_count])

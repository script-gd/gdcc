extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var result = float(target.call("vector_y_from_int_args"))
    if result == 2.0:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Builtin constructor int-to-float validation failed: result=%s" % result)

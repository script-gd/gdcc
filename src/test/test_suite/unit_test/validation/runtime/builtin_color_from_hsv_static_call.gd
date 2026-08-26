extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var red = target.call("make_red")
    if typeof(red) != TYPE_COLOR or not red.is_equal_approx(Color(1.0, 0.0, 0.0, 1.0)):
        push_error("Builtin static direct call Color.from_hsv returned a wrong color: %s" % red)
        return

    var custom = target.call("make_custom", 0.5, 1.0, 1.0)
    if typeof(custom) != TYPE_COLOR or absf(custom.a - 0.5) > 0.001 or absf(custom.h - 0.5) > 0.01:
        push_error("Builtin static direct call Color.from_hsv with arguments failed: %s" % custom)
        return

    print("__UNIT_TEST_PASS_MARKER__")

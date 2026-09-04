extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    # In-class bare / self. / static call sites complete defaults at compile time:
    # 140 + 102 + 240 + 13 + 9 + 100 + 101 = 705.
    var total = int(target.call("run_checks"))
    if total != 705:
        push_error("internal exact/static default completion failed: %d" % total)
        return

    # Static source function with defaults through the ClassDB dynamic path.
    var static_partial = int(ClassDB.class_call_static("DefaultArgsInternalExact", "sadd", 2))
    if static_partial != 14:
        push_error("static default partial fill failed: %d" % static_partial)
        return

    var static_full = int(ClassDB.class_call_static("DefaultArgsInternalExact", "sadd", 2, 3, 4))
    if static_full != 9:
        push_error("static default full args failed: %d" % static_full)
        return

    print("__UNIT_TEST_PASS_MARKER__")

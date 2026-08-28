extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var initial_total = int(target.call("read_total"))
    var first = int(target.call("bump", 5))
    var second = int(target.call("bump", 2))
    var cross_method = int(target.call("read_counter"))
    var class_named = int(target.call("write_and_read", 42))
    var label = String(target.call("read_label"))
    var overwritten = String(target.call("overwrite_label", "next"))
    var reread = String(target.call("read_label"))
    if initial_total == 10 and first == 5 and second == 7 and cross_method == 7 and class_named == 42 and label == "init" and overwritten == "next" and reread == "next":
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Static var basic validation failed.")

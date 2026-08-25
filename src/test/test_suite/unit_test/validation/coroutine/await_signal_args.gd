extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("start_zero")
    target.call("start_one")
    target.call("start_many")
    target.call("emit_zero")
    target.call("emit_one", 23)
    target.call("emit_many", 31, "many")

    var many_result = target.call("read_many_result")
    if not bool(target.call("read_zero_done")) or target.call("read_zero_result") != null:
        push_error("Zero-argument signal resume contract failed.")
        return
    if int(target.call("read_one_result")) != 23:
        push_error("Single-argument signal resume contract failed.")
        return
    if many_result.size() != 2 or int(many_result[0]) != 31 or String(many_result[1]) != "many":
        push_error("Multi-argument signal resume contract failed: %s" % [many_result])
        return

    print("__UNIT_TEST_PASS_MARKER__")

extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var state = target.call("produce")
    if typeof(state) != TYPE_OBJECT:
        push_error("Suspended Variant coroutine did not expose a state object.")
        return

    target.call("emit_release", 40)
    target.call("start_consume", state)
    if not bool(target.call("read_done")) or int(target.call("read_result")) != 42:
        push_error("Dynamic await of an already-completed state missed the done fast path.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

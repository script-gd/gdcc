extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var state = target.call("produce")
    if typeof(state) != TYPE_OBJECT or not state.has_signal("completed"):
        push_error("Compiled coroutine state did not expose the completed signal.")
        return

    target.call_deferred("emit_release", 21)
    var result = await state.completed
    if int(result) != 42:
        push_error("Compiled state completed signal carried the wrong result: %s" % result)
        return

    print("__UNIT_TEST_PASS_MARKER__")

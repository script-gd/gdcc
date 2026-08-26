extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var state = ClassDB.class_call_static("StaticAwaitCalledFromGodot", "produce", target)
    if typeof(state) != TYPE_OBJECT or not state.has_signal("completed"):
        push_error("Static coroutine called from Godot did not return a state object: %s" % state)
        return

    target.call_deferred("emit_release", 21)
    var result = await state.completed
    if int(result) != 42:
        push_error("Static coroutine called from Godot carried the wrong result: %s" % result)
        return

    print("__UNIT_TEST_PASS_MARKER__")

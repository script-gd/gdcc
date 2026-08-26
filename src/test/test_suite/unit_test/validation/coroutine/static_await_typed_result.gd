extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("run")
    if bool(target.call("read_done")):
        push_error("Static coroutine chain completed before its signal.")
        return

    target.call("emit_release", 41)
    if not bool(target.call("read_done")) or int(target.call("read_result")) != 42:
        push_error("Typed result did not cross the static coroutine boundary.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

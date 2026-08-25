extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("setup")
    target.call("emit_ping")
    if int(target.get("result")) != -1:
        push_error("connected coroutine lambda did not suspend after engine-invoked call.")
        return

    target.call("emit_step", 33)
    if int(target.get("result")) != 33:
        push_error("connected coroutine lambda did not resume after its suspended state was dropped by the engine.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

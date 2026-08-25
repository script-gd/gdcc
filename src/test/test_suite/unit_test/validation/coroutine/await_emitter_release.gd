extends Node

class EphemeralEmitter extends Node:
    signal released

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var emitter = EphemeralEmitter.new()
    add_child(emitter)
    target.call("start_wait", emitter.released)
    emitter.queue_free()
    await get_tree().process_frame
    await get_tree().process_frame
    if bool(target.call("read_resumed")):
        push_error("Emitter deletion incorrectly resumed the abandoned awaiter.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

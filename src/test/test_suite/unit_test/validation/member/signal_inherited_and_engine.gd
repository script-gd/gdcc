extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var ready_sig = target.call("copy_ready", target)
    var button := Button.new()
    add_child(button)
    var pressed_sig = target.call("copy_pressed", button)
    if typeof(ready_sig) != TYPE_SIGNAL or typeof(pressed_sig) != TYPE_SIGNAL \
            or not bool(target.call("ready_matches", target)) \
            or not bool(target.call("pressed_matches", button)):
        push_error("Engine signal value read failed: ready=%s pressed=%s" % [
            typeof(ready_sig),
            typeof(pressed_sig)
        ])
        return

    var child = target.call("make_child")
    if child == null:
        push_error("Failed to construct inherited-signal child.")
        return
    var wire_err = int(target.call("wire_child", child))
    var hits = int(target.call("fire_child", child))
    if wire_err != 0 or hits != 1:
        push_error("Inherited signal emit failed: err=%s hits=%s" % [wire_err, hits])
        return

    print("__UNIT_TEST_PASS_MARKER__")

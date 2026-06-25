# gdcc-test: output_not_contains=engine method call failed: Engine.get_frames_drawn
extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var later_local = int(target.call("frames_before_later_local"))
    if later_local < 0:
        push_error("Singleton receiver drifted to the later local shadow.")
        return

    var self_initializer = int(target.call("frames_from_self_named_initializer"))
    if self_initializer < 0:
        push_error("Singleton receiver drifted to the self-referential local initializer.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

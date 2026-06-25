# gdcc-test: output_not_contains=engine method call failed: Engine.get_frames_drawn
# gdcc-test: output_not_contains=engine method call failed: Engine.set_time_scale
# gdcc-test: output_not_contains=engine method call failed: Input.is_action_pressed
extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    if int(target.call("read_startup_frames")) < 0:
        push_error("Singleton receiver property initializer returned a negative frame count.")
        return

    if int(target.call("read_frames_drawn")) < 0:
        push_error("Singleton receiver method call returned a negative frame count.")
        return

    if abs(float(target.call("reset_engine_time_scale")) - 1.0) > 0.0001:
        push_error("Singleton receiver void call did not restore Engine time scale.")
        return

    var pressed_state = target.call("read_ui_accept_pressed_state")
    if typeof(pressed_state) != TYPE_BOOL:
        push_error("Singleton receiver argument call returned non-bool state: %d" % [typeof(pressed_state)])
        return

    print("__UNIT_TEST_PASS_MARKER__")

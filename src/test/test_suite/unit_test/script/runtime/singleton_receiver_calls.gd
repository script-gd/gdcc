class_name SingletonReceiverCallsSmoke
extends Node

var startup_frames: int = Engine.get_frames_drawn()

func read_startup_frames() -> int:
    return startup_frames

func read_frames_drawn() -> int:
    return Engine.get_frames_drawn()

func reset_engine_time_scale() -> float:
    Engine.set_time_scale(1.0)
    return Engine.get_time_scale()

func read_ui_accept_pressed_state() -> bool:
    # The pressed state is environment-dependent; the stable contract is the typed singleton call.
    return Input.is_action_pressed(&"ui_accept", true)

extends Node

const EXPECTED_PITCH_DEGREE = 45.0
const TARGET_NODE_NAME = "RotatingCameraNode"

func _ready() -> void:
    var camera = get_parent().get_node_or_null(TARGET_NODE_NAME)
    if camera == null:
        push_error("Camera node missing.")
        return

    var pitch = float(camera.get("pitch_degree"))
    print("Pitch degree:", pitch)
    if absf(pitch - EXPECTED_PITCH_DEGREE) <= 0.001:
        print("Pitch check passed.")
    else:
        push_error("Pitch check failed: expected " + str(EXPECTED_PITCH_DEGREE) + ", got " + str(pitch))

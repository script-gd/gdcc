extends Node

const REQUIRED_PHYSICS_FRAMES := 3

func _ready() -> void:
    # Wait for a few physics frames so the engine can drive the target virtual naturally.
    for _i in range(REQUIRED_PHYSICS_FRAMES):
        await get_tree().physics_frame

    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var process_count = int(target.call("get_physics_process_count_value"))
    var last_delta = float(target.call("get_last_physics_delta_value"))
    if process_count > 0 and last_delta > 0.0:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("PhysicsProcessVirtualRuntimeSmoke validation failed.")

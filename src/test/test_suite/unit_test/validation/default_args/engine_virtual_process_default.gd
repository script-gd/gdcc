extends Node

const REQUIRED_PROCESS_FRAMES := 3

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    # The engine drives _process with the real delta; the declared default is never used.
    for _i in range(REQUIRED_PROCESS_FRAMES):
        await get_tree().process_frame

    var process_count = int(target.call("get_process_count_value"))
    if process_count <= 0:
        push_error("engine virtual with default was not dispatched: count=%d" % process_count)
        return

    var delta_sum = float(target.call("get_delta_sum_value"))
    if delta_sum <= 0.0:
        push_error("engine virtual received default-like delta: sum=%s" % delta_sum)
        return

    print("__UNIT_TEST_PASS_MARKER__")

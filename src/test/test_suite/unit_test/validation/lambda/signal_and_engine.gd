extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var connect_err = int(target.call("connect_labeled"))
    var labeled = String(target.call("fire_labeled", "ok"))
    if connect_err != 0 or labeled != "ok":
        push_error("labeled lambda connect/emit failed: err=%s value=%s" % [connect_err, labeled])
        return

    var one_shot_err = int(target.call("connect_one_shot"))
    var after_first = int(target.call("fire_one_shot", "once"))
    var after_second = int(target.call("fire_one_shot", "twice"))
    if one_shot_err != 0 or after_first != 1 or after_second != 1:
        push_error("CONNECT_ONE_SHOT lambda failed: err=%s first=%s second=%s" % [
            one_shot_err,
            after_first,
            after_second
        ])
        return

    var mapped = int(target.call("map_double"))
    if mapped != 246:
        push_error("Array.map lambda failed: %s" % mapped)
        return

    var filtered = int(target.call("filter_even"))
    if filtered != 22:
        push_error("Array.filter lambda failed: %s" % filtered)
        return

    print("__UNIT_TEST_PASS_MARKER__")

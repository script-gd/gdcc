extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var via_self = int(target.call("hit_via_self"))
    var via_other = int(target.call("hit_via_other_instance"))
    var final_hits = int(target.call("read_hits"))
    if via_self == 1 and via_other == 2 and final_hits == 2:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Static var instance access validation failed.")

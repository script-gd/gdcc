extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    if int(target.call("get_init_value")) != 0:
        push_error("@onready field did not hold the type default during _init.")
        return
    if int(target.call("get_ready_value")) != 41:
        push_error("@onready field did not hold the initializer value inside _ready.")
        return
    if int(target.call("get_value")) != 41:
        push_error("@onready field value did not survive _ready.")
        return
    print("__UNIT_TEST_PASS_MARKER__")

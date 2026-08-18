extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var connect_err = int(target.call("connect_lambda"))
    if connect_err != 0:
        push_error("Lambda signal connect failed: %s" % connect_err)
        return

    target.call("fire")
    var hits = int(target.call("read_hits"))
    if hits != 1:
        push_error("Lambda signal connect did not fire exactly once: hits=%s" % hits)
        return

    print("__UNIT_TEST_PASS_MARKER__")

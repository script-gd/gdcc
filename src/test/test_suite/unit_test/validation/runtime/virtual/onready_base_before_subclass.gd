extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("run_case")
    var log: Array = target.call("get_log")
    if log.size() != 3:
        push_error("Onready chain did not run the expected number of initializers: " + str(log))
        return
    if log[0] != "base" or log[1] != "sub" or log[2] != "sub_ready":
        push_error("Onready order mismatch (expected base -> sub -> user _ready): " + str(log))
        return
    print("__UNIT_TEST_PASS_MARKER__")

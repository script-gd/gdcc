extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var reopened_int = int(target.call("reopen_int", 41))
    var reopened_string = String(target.call("reopen_string", 12))
    var exact_vector_sum = int(target.call("exact_vector_sum"))
    if reopened_int == 41 and reopened_string == "12" and exact_vector_sum == 6:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error(
            "Builtin Variant scalar constructor validation failed: int=%d string=%s vector_sum=%d"
            % [reopened_int, reopened_string, exact_vector_sum]
        )

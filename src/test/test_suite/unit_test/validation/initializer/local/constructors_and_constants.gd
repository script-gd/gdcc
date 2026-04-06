extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    if not bool(target.call("constructor_matches")):
        push_error("Builtin constructor local initializer validation failed.")
        return

    if not bool(target.call("read_type_meta_values")):
        push_error("Type-meta constant local initializer validation failed.")
        return

    if int(target.call("empty_array_size")) != 0:
        push_error("Array constructor local initializer validation failed.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

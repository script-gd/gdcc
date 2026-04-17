extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var item_count = int(target.call("item_count_after_add_separator_default_text"))
    if item_count == 1:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Engine OptionButton default-argument validation failed: %s" % [item_count])

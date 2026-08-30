# gdcc-test: output_contains=assert_object_live failed: object
extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    # assert_object_live failure prints and returns from the compiled function; it does not abort Godot.
    target.call("copy_from_null")
    target.call("copy_from_freed")
    print("__UNIT_TEST_PASS_MARKER__")

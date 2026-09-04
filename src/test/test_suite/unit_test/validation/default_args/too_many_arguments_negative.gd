# gdcc-test: output_contains=Expected 2 argument(s).
# gdcc-test: output_not_contains=default_args too-many after bad call.
extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var ok = int(target.call("pair", 1, 2))
    if ok != 12:
        push_error("pair(1, 2) expected 12, got %d" % ok)
        return

    # Emit the pass marker immediately before the intentionally failing call.
    print("__UNIT_TEST_PASS_MARKER__")
    target.call("pair", 1, 2, 3)
    print("default_args too-many after bad call.")

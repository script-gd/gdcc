# gdcc-test: output_contains=Expected 2 argument(s).
# gdcc-test: output_not_contains=default_args too-few after bad call.
extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var ok = int(target.call("need", 1, 2))
    if ok != 129:
        push_error("need(1, 2) expected 129, got %d" % ok)
        return

    # Emit the pass marker immediately before the intentionally failing call.
    print("__UNIT_TEST_PASS_MARKER__")
    target.call("need", 1)
    print("default_args too-few after bad call.")

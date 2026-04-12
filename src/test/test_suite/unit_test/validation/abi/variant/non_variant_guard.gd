# gdcc-test: output_contains=Cannot convert argument 2 from PackedInt32Array to int.
# gdcc-test: output_not_contains=Cannot convert argument 2 from PackedInt32Array to Nil.
# gdcc-test: output_not_contains=frontend Variant non-Variant guard after bad call.
extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var payload := PackedInt32Array()
    payload.push_back(4)

    # Emit the pass marker immediately before the intentionally failing boundary call.
    print("__UNIT_TEST_PASS_MARKER__")
    target.call("accept_int", payload)
    print("frontend Variant non-Variant guard after bad call.")

# gdcc-test: output_contains_any=The dictionary of argument || Invalid type in function
# gdcc-test: output_not_contains=frontend typed dictionary method wrong-typed guard after bad call.
extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var exact: Dictionary[StringName, Node] = {
        &"root": Node.new(),
    }
    if int(target.call("accept_payloads", exact)) != 11 or int(target.call("read_accept_calls")) != 1:
        push_error("Typed dictionary wrong-typed guard setup failed.")
        return

    var wrong: Dictionary[StringName, RefCounted] = {
        &"worker": RefCounted.new(),
    }

    # Emit the pass marker immediately before the intentionally failing boundary call.
    print("__UNIT_TEST_PASS_MARKER__")
    target.call("accept_payloads", wrong)
    print("frontend typed dictionary method wrong-typed guard after bad call.")

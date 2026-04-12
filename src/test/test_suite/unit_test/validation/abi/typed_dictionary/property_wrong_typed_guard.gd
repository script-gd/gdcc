# gdcc-test: output_contains_any=The dictionary of argument || Invalid assignment of property or key
# gdcc-test: output_not_contains=frontend typed dictionary property wrong-typed guard after bad set.
extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var exact: Dictionary[StringName, Node] = {
        &"root": Node.new(),
    }
    target.payloads = exact
    if int(target.call("read_payload_size")) != 1:
        push_error("Typed dictionary property wrong-typed guard setup failed.")
        return

    var wrong: Dictionary[StringName, RefCounted] = {
        &"worker": RefCounted.new(),
    }

    # Emit the pass marker immediately before the intentionally failing property set.
    print("__UNIT_TEST_PASS_MARKER__")
    target.payloads = wrong
    print("frontend typed dictionary property wrong-typed guard after bad set.")

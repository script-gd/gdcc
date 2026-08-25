# gdcc-test: output_contains=typed non-Variant returns cannot carry the coroutine state
extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var immediate = int(target.call("typed_suspend"))
    if immediate != 0 or bool(target.call("read_completed")):
        push_error("Typed suspended engine boundary did not return its default value.")
        return

    target.call("emit_release")
    if not bool(target.call("read_completed")):
        push_error("Detached typed coroutine did not finish in the background.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

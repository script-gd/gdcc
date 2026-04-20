extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var failures = []

    var trace = String(target.call("build_trace", 4))
    if trace != "zero|one|rest|rest":
        failures.push_back("build_trace expected zero|one|rest|rest, got: %s" % [trace])

    var added = int(target.call("pass_then_add", 5))
    if added != 7:
        failures.push_back("pass_then_add expected 7, got: %d" % [added])

    var returned = String(target.call("comment_only_return"))
    if returned != "comment-ok":
        failures.push_back("comment_only_return expected comment-ok, got: %s" % [returned])

    if failures.is_empty():
        print("__UNIT_TEST_PASS_MARKER__")
        return

    for failure in failures:
        push_error(failure)

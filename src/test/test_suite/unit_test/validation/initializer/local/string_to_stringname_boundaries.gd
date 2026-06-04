extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var failures = []
    _expect_name(failures, target.call("read_ready_name"), "property-ready", "property initializer")
    _expect_name(failures, target.call("local_name"), "local-alpha", "local initializer")
    _expect_name(failures, target.call("assignment_name", "assigned-beta"), "assigned-beta", "assignment")
    _expect_name(failures, target.call("property_store_name", "stored-gamma"), "stored-gamma", "property store")
    _expect_name(failures, target.call("fixed_arg_name", "argument-delta"), "argument-delta", "fixed argument")
    _expect_name(failures, target.call("return_text_as_name", "return-epsilon"), "return-epsilon", "return slot")

    if failures.is_empty():
        print("__UNIT_TEST_PASS_MARKER__")
        return

    for failure in failures:
        push_error(failure)

func _expect_name(failures: Array, value: Variant, expected: String, label: String) -> void:
    if typeof(value) != TYPE_STRING_NAME:
        failures.push_back("%s expected TYPE_STRING_NAME, got type %d" % [label, typeof(value)])
        return

    var actual = String(value)
    if actual != expected:
        failures.push_back("%s expected %s, got %s" % [label, expected, actual])

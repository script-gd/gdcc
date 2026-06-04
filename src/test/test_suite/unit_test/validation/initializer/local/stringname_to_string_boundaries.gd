extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var failures = []
    _expect_text(failures, target.call("read_ready_text"), "property-ready", "property initializer")
    _expect_text(failures, target.call("local_text"), "local-alpha", "local initializer")
    _expect_text(failures, target.call("assignment_text", &"assigned-beta"), "assigned-beta", "assignment")
    _expect_text(failures, target.call("property_store_text", &"stored-gamma"), "stored-gamma", "property store")
    _expect_text(failures, target.call("fixed_arg_text", &"argument-delta"), "argument-delta", "fixed argument")
    _expect_text(failures, target.call("return_name_as_text", &"return-epsilon"), "return-epsilon", "return slot")

    if failures.is_empty():
        print("__UNIT_TEST_PASS_MARKER__")
        return

    for failure in failures:
        push_error(failure)

func _expect_text(failures: Array, value: Variant, expected: String, label: String) -> void:
    if typeof(value) != TYPE_STRING:
        failures.push_back("%s expected TYPE_STRING, got type %d" % [label, typeof(value)])
        return

    var actual = String(value)
    if actual != expected:
        failures.push_back("%s expected %s, got %s" % [label, expected, actual])

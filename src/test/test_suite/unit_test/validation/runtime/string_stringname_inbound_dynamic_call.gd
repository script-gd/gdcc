# gdcc-test: output_contains=Cannot convert argument 2 from NodePath to StringName.
# gdcc-test: output_not_contains=string-stringname inbound dynamic call after bad call.
extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var failures = []
    _expect_name(failures, target.call("take_name", "from-text"), "from-text", "String to StringName inbound")
    _expect_name(failures, target.call("take_name", &"exact-name"), "exact-name", "exact StringName inbound")
    _expect_text(failures, target.call("take_text", &"from-name"), "from-name", "StringName to String inbound")
    _expect_text(failures, target.call("take_text", "exact-text"), "exact-text", "exact String inbound")

    if not failures.is_empty():
        for failure in failures:
            push_error(failure)
        return

    print("__UNIT_TEST_PASS_MARKER__")
    target.call("take_name", NodePath("bad"))
    print("string-stringname inbound dynamic call after bad call.")

func _expect_name(failures: Array, value: Variant, expected: String, label: String) -> void:
    if typeof(value) != TYPE_STRING_NAME:
        failures.push_back("%s expected TYPE_STRING_NAME, got type %d" % [label, typeof(value)])
        return

    var actual = String(value)
    if actual != expected:
        failures.push_back("%s expected %s, got %s" % [label, expected, actual])

func _expect_text(failures: Array, value: Variant, expected: String, label: String) -> void:
    if typeof(value) != TYPE_STRING:
        failures.push_back("%s expected TYPE_STRING, got type %d" % [label, typeof(value)])
        return

    var actual = String(value)
    if actual != expected:
        failures.push_back("%s expected %s, got %s" % [label, expected, actual])

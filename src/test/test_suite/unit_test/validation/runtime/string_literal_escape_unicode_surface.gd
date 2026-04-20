extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var failures = []

    var expected_escaped = "\"line\"\n中🙂\t尾巴\\"
    var escaped_value = String(target.call("escaped_payload"))
    if escaped_value != expected_escaped:
        failures.push_back(
                "escaped_payload expected %s, got %s" % [expected_escaped.c_escape(), escaped_value.c_escape()]
        )

    var unicode_value = String(target.call("unicode_escape_payload"))
    if unicode_value != "你好-🙂":
        failures.push_back("unicode_escape_payload expected 你好-🙂, got: %s" % [unicode_value])

    var name_value = target.call("escaped_name_payload")
    if typeof(name_value) != TYPE_STRING_NAME:
        failures.push_back("escaped_name_payload expected TYPE_STRING_NAME, got type: %d" % [typeof(name_value)])
    elif String(name_value) != "组件🙂":
        failures.push_back("escaped_name_payload expected 组件🙂, got: %s" % [String(name_value)])

    var name_matches = bool(target.call("escaped_name_matches"))
    if not name_matches:
        failures.push_back("escaped_name_matches expected true.")

    var true_branch = String(target.call("classify_escape", true))
    if true_branch != "alpha\"beta\"":
        failures.push_back("classify_escape(true) expected alpha\"beta\", got: %s" % [true_branch])

    var false_branch = String(target.call("classify_escape", false))
    if false_branch != "line\nbeta":
        failures.push_back("classify_escape(false) expected line\\nbeta, got: %s" % [false_branch.c_escape()])

    if failures.is_empty():
        print("__UNIT_TEST_PASS_MARKER__")
        return

    for failure in failures:
        push_error(failure)

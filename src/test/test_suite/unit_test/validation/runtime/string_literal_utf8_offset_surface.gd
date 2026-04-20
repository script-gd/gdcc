extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var failures = []

    var prefixed = String(target.call("multibyte_prefix_value"))
    if prefixed != "尾巴\n\"段落\"":
        failures.push_back("multibyte_prefix_value expected 尾巴\\n\"段落\", got: %s" % [prefixed.c_escape()])

    var true_branch = String(target.call("branch_value", true))
    if true_branch != "分支一🙂":
        failures.push_back("branch_value(true) expected 分支一🙂, got: %s" % [true_branch])

    var false_branch = String(target.call("branch_value", false))
    if false_branch != "分支二\t终点":
        failures.push_back("branch_value(false) expected 分支二\\t终点, got: %s" % [false_branch.c_escape()])

    var escaped = String(target.call("escaped_after_multibyte"))
    if escaped != "火🚀":
        failures.push_back("escaped_after_multibyte expected 火🚀, got: %s" % [escaped])

    var name_value = target.call("build_name")
    if typeof(name_value) != TYPE_STRING_NAME:
        failures.push_back("build_name expected TYPE_STRING_NAME, got type: %d" % [typeof(name_value)])
    elif String(name_value) != "路径/节点🙂":
        failures.push_back("build_name expected 路径/节点🙂, got: %s" % [String(name_value)])

    if failures.is_empty():
        print("__UNIT_TEST_PASS_MARKER__")
        return

    for failure in failures:
        push_error(failure)

extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var failures = []

    var concat_value = String(target.call("concat_internal"))
    if concat_value != "gdcc-engine-suite":
        failures.push_back("concat_internal expected gdcc-engine-suite, got: %s" % [concat_value])

    var substr_value = String(target.call("substr_internal"))
    if substr_value != "cde":
        failures.push_back("substr_internal expected cde, got: %s" % [substr_value])

    var compare_value = bool(target.call("compare_internal"))
    if not compare_value:
        failures.push_back("compare_internal expected true.")

    var alpha_value = String(target.call("classify_internal", "alpha"))
    if alpha_value != "alpha:ok":
        failures.push_back("classify_internal(alpha) expected alpha:ok, got: %s" % [alpha_value])

    var beta_value = String(target.call("classify_internal", "beta"))
    if beta_value != "beta:ok":
        failures.push_back("classify_internal(beta) expected beta:ok, got: %s" % [beta_value])

    var fallback_value = String(target.call("classify_internal", "gamma"))
    if fallback_value != "fallback:\"gamma\"":
        failures.push_back("classify_internal(gamma) expected fallback:\"gamma\", got: %s" % [fallback_value])

    var expected_escape = "line\nbreak\t\"quoted\""
    var escape_value = String(target.call("escape_internal"))
    if escape_value != expected_escape:
        failures.push_back(
                "escape_internal expected %s, got %s" % [expected_escape.c_escape(), escape_value.c_escape()]
        )

    if failures.is_empty():
        print("__UNIT_TEST_PASS_MARKER__")
        return

    for failure in failures:
        push_error(failure)

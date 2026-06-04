extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var named_values: Dictionary[StringName, int] = {}
    var keyed_values: Dictionary[String, int] = {}
    var named_payloads: Dictionary[StringName, PackedInt32Array] = {}
    var keyed_payloads: Dictionary[String, PackedInt32Array] = {}

    var summary = int(target.call(
            "exercise_key_routes",
            named_values,
            keyed_values,
            named_payloads,
            keyed_payloads,
            13,
            17
    ))

    var caller_visible = int(named_values[&"score"]) == 7 \
            and int(keyed_values["score"]) == 11 \
            and int(named_payloads[&"bag"][0]) == 13 \
            and named_payloads[&"bag"].size() == 1 \
            and int(keyed_payloads["bag"][0]) == 17 \
            and keyed_payloads["bag"].size() == 1

    if summary == 14231417 and caller_visible:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error(
                "String/StringName dictionary key validation failed: summary=%d caller_visible=%s"
                % [summary, str(caller_visible)]
        )

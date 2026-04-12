extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var payload := PackedInt32Array()
    payload.push_back(4)
    payload.push_back(5)
    payload.push_back(6)

    var accepted = int(target.call("accept_variant", payload))
    var echoed: Variant = target.echo_variant(payload)
    if accepted == typeof(payload) * 10 + 1 and echoed is PackedInt32Array and echoed.size() == 3 and int(target.call("read_variant_calls")) == 1:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Variant method roundtrip validation failed.")

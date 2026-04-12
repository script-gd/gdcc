extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var hidden := PackedInt32Array()
    hidden.push_back(7)
    hidden.push_back(8)
    var visible := PackedStringArray()
    visible.push_back("alpha")
    visible.push_back("beta")
    visible.push_back("gamma")

    target.payload = hidden
    target.visible_payload = visible
    target.score = 9

    var reopened_hidden: Variant = target.payload
    var reopened_visible: Variant = target.visible_payload
    if reopened_hidden is PackedInt32Array and reopened_hidden.size() == 2 and int(target.call("read_payload_size")) == 2 and reopened_visible is PackedStringArray and reopened_visible.size() == 3 and int(target.call("read_visible_payload_size")) == 3 and int(target.score) == 9 and int(target.call("read_score")) == 9:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Variant property roundtrip validation failed.")

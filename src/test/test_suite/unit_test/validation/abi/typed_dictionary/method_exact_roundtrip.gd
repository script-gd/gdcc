extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var exact: Dictionary[StringName, Node] = {
        &"root": Node.new(),
        &"leaf": null,
    }
    if int(target.call("accept_payloads", exact)) == 12 and int(target.call("read_accept_calls")) == 1:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Typed dictionary exact method validation failed.")

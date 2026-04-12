extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var exact: Dictionary[StringName, Node] = {
        &"root": target,
        &"leaf": null,
    }
    var payloads: Dictionary[StringName, Node] = target.echo_payloads(exact)
    if payloads.get_typed_key_builtin() == TYPE_STRING_NAME and payloads.get_typed_value_builtin() == TYPE_OBJECT and payloads.get_typed_value_class_name() == &"Node" and payloads.get_typed_value_script() == null and payloads.size() == 2 and payloads[&"root"] == target and payloads[&"leaf"] == null:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Typed dictionary return roundtrip validation failed.")

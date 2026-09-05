extends Node

var failures := 0

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var props := {}
    for entry in target.get_property_list():
        props[entry["name"]] = entry

    # Object exports: hint_string and the property class_name slot both carry the property type
    # class name, not the owner class name.
    _check(props, "texture", PROPERTY_HINT_RESOURCE_TYPE, "Texture2D", "Texture2D")
    _check(props, "target_node", PROPERTY_HINT_NODE_TYPE, "Node2D", "Node2D")

    if failures == 0:
        print("__UNIT_TEST_PASS_MARKER__")

func _check(props: Dictionary, pname: String, phint: int, phint_string: String, pclass_name: String) -> void:
    if not props.has(pname):
        failures += 1
        push_error("Missing property: " + pname)
        return
    var p: Dictionary = props[pname]
    if int(p["type"]) != TYPE_OBJECT or int(p["hint"]) != phint or String(p["hint_string"]) != phint_string or String(p["class_name"]) != pclass_name or int(p["usage"]) != PROPERTY_USAGE_DEFAULT:
        failures += 1
        push_error("Property '%s' metadata mismatch: got type=%d hint=%d hint_string='%s' class_name='%s' usage=%d" % [
            pname,
            int(p["type"]),
            int(p["hint"]),
            String(p["hint_string"]),
            String(p["class_name"]),
            int(p["usage"]),
        ])

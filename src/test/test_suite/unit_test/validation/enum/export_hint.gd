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

    # Bare @export on a script-enum-typed property publishes PROPERTY_HINT_ENUM with the
    # generated capitalize(Name):value hint_string; explicit variants and unexported
    # properties keep their previous metadata.
    _check(props, "current", TYPE_INT, PROPERTY_HINT_ENUM, "Idle:0,Jump:5", "", PROPERTY_USAGE_DEFAULT)
    _check(props, "heading", TYPE_INT, PROPERTY_HINT_ENUM, "Move Left:0,Move Right:1", "", PROPERTY_USAGE_DEFAULT)
    _check(props, "ranged", TYPE_INT, PROPERTY_HINT_RANGE, "0,10", "", PROPERTY_USAGE_DEFAULT)
    _check(props, "untouched", TYPE_INT, PROPERTY_HINT_NONE, "", "", PROPERTY_USAGE_NO_EDITOR)

    if int(target.call("read_current")) != 5:
        failures += 1
        push_error("current initializer value mismatch")

    if failures == 0:
        print("__UNIT_TEST_PASS_MARKER__")

func _check(props: Dictionary, pname: String, ptype: int, phint: int, phint_string: String, pclass_name: String, pusage: int) -> void:
    if not props.has(pname):
        failures += 1
        push_error("Missing property: " + pname)
        return
    var p: Dictionary = props[pname]
    if int(p["type"]) != ptype or int(p["hint"]) != phint or String(p["hint_string"]) != phint_string or String(p["class_name"]) != pclass_name or int(p["usage"]) != pusage:
        failures += 1
        push_error("Property '%s' metadata mismatch: got type=%d hint=%d hint_string='%s' class_name='%s' usage=%d" % [
            pname,
            int(p["type"]),
            int(p["hint"]),
            String(p["hint_string"]),
            String(p["class_name"]),
            int(p["usage"]),
        ])

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

    # Every exported property publishes PROPERTY_USAGE_DEFAULT (storage | editor); the Variant
    # export additionally carries NIL_IS_VARIANT, and the unexported property keeps NO_EDITOR.
    _check(props, "anything", TYPE_NIL, PROPERTY_HINT_NONE, "", "", PROPERTY_USAGE_DEFAULT | PROPERTY_USAGE_NIL_IS_VARIANT)
    _check(props, "speed", TYPE_FLOAT, PROPERTY_HINT_RANGE, "0,20,0.5", "", PROPERTY_USAGE_DEFAULT)
    _check(props, "level", TYPE_INT, PROPERTY_HINT_RANGE, "0,100,1,or_greater", "", PROPERTY_USAGE_DEFAULT)
    _check(props, "archetype", TYPE_STRING, PROPERTY_HINT_ENUM, "Warrior,Mage", "", PROPERTY_USAGE_DEFAULT)
    _check(props, "pace", TYPE_INT, PROPERTY_HINT_ENUM, "Slow,Fast", "", PROPERTY_USAGE_DEFAULT)
    _check(props, "elements", TYPE_INT, PROPERTY_HINT_FLAGS, "Fire,Water,Earth", "", PROPERTY_USAGE_DEFAULT)
    _check(props, "layers_2d", TYPE_INT, PROPERTY_HINT_LAYERS_2D_RENDER, "", "", PROPERTY_USAGE_DEFAULT)
    _check(props, "icon_path", TYPE_STRING, PROPERTY_HINT_FILE, "*.png", "", PROPERTY_USAGE_DEFAULT)
    _check(props, "folder", TYPE_STRING, PROPERTY_HINT_DIR, "", "", PROPERTY_USAGE_DEFAULT)
    _check(props, "global_path", TYPE_STRING, PROPERTY_HINT_GLOBAL_FILE, "*.txt", "", PROPERTY_USAGE_DEFAULT)
    _check(props, "global_folder", TYPE_STRING, PROPERTY_HINT_GLOBAL_DIR, "", "", PROPERTY_USAGE_DEFAULT)
    _check(props, "description", TYPE_STRING, PROPERTY_HINT_MULTILINE_TEXT, "", "", PROPERTY_USAGE_DEFAULT)
    _check(props, "prompt", TYPE_STRING, PROPERTY_HINT_PLACEHOLDER_TEXT, "Enter name...", "", PROPERTY_USAGE_DEFAULT)
    _check(props, "easing", TYPE_FLOAT, PROPERTY_HINT_EXP_EASING, "attenuation,positive_only", "", PROPERTY_USAGE_DEFAULT)
    _check(props, "tint", TYPE_COLOR, PROPERTY_HINT_COLOR_NO_ALPHA, "", "", PROPERTY_USAGE_DEFAULT)
    _check(props, "target_path", TYPE_NODE_PATH, PROPERTY_HINT_NODE_PATH_VALID_TYPES, "Node2D,Sprite2D", "", PROPERTY_USAGE_DEFAULT)
    _check(props, "hidden", TYPE_INT, PROPERTY_HINT_NONE, "", "", PROPERTY_USAGE_NO_EDITOR)

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

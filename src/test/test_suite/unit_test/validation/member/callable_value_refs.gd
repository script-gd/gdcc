extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var abs_value = target.call("abs_via_callable", Vector2(-3, 4))
    var remaining = int(target.call("clear_via_callable", [1, 2, 3]))
    var static_label = String(target.call("static_via_callable", "ok"))
    var parsed = target.call("engine_static_via_callable", "{\"n\":1}")
    var lerped = float(target.call("utility_via_callable", 0.0, 10.0, 0.5))
    var hits = int(target.call("connect_builtin_and_static", Vector2.ONE))
    var valid = bool(target.call("standalone_valid"))

    if abs_value != Vector2(3, 4):
        push_error("vec.abs callable failed: %s" % abs_value)
        return
    if remaining != 0:
        push_error("array.clear callable did not share the original array: %s" % remaining)
        return
    if static_label != "ok-static":
        push_error("GDCC static callable failed: %s" % static_label)
        return
    if typeof(parsed) != TYPE_DICTIONARY or int(parsed["n"]) != 1:
        push_error("JSON.parse_string callable failed: %s" % parsed)
        return
    if lerped != 5.0:
        push_error("lerp callable failed: %s" % lerped)
        return
    if hits != 1:
        push_error("connect via materialized Callable failed: %s" % hits)
        return
    if not valid:
        push_error("standalone trampoline is_valid() expected true (intentional GDCC deviation from Godot native-class quirk)")
        return

    print("__UNIT_TEST_PASS_MARKER__")

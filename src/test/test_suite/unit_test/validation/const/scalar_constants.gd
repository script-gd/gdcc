extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var ok := true
    if int(target.call("scalar_sum")) != 61:
        push_error("scalar_sum failed")
        ok = false
    var doubled_ratio := float(target.call("doubled_ratio"))
    if not is_finite(doubled_ratio) or not is_equal_approx(doubled_ratio, 3.0):
        push_error("doubled_ratio failed")
        ok = false
    if not bool(target.call("flag_on")):
        push_error("flag_on failed")
        ok = false
    if str(target.call("greet")) != "gdcc!":
        push_error("greet failed")
        ok = false
    var widened := float(target.call("widened"))
    if not is_finite(widened) or not is_equal_approx(widened, 2.25):
        push_error("widened failed")
        ok = false
    if int(target.call("typed")) != 7:
        push_error("typed failed")
        ok = false
    if not bool(target.call("globals_ok")):
        push_error("globals_ok failed")
        ok = false

    # Constants are compile-time facts only: they must not register as ClassDB
    # properties or integer constants on the compiled class.
    for property in target.get_property_list():
        if String(property.name) in ["LIMIT", "RATIO", "FLAG", "GREETING", "TYPED", "WIDENED", "DERIVED", "PI_VAL", "OK_VAL", "PRESET"]:
            push_error("const leaked into property list: %s" % property.name)
            ok = false
    var registered := ClassDB.class_get_integer_constant_list("ConstScalarConstants", true)
    for const_name in ["LIMIT", "RATIO", "FLAG", "GREETING", "TYPED", "WIDENED", "DERIVED", "PI_VAL", "OK_VAL", "PRESET"]:
        if const_name in registered:
            push_error("const leaked into ClassDB constants: %s" % const_name)
            ok = false

    if ok:
        print("__UNIT_TEST_PASS_MARKER__")

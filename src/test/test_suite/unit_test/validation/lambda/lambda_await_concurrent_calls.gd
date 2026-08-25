extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var cb: Callable = target.call("make_cb")
    var state_a = cb.call(1)
    var state_b = cb.call(2)
    if state_a == null or state_b == null or state_a == state_b:
        push_error("concurrent calls did not produce two distinct suspended states.")
        return
    if int(target.get("order")) != 12:
        push_error("both calls must run to the await point before any emit: order=%s" % target.get("order"))
        return

    target.call("emit_value", 7)
    if int(target.get("order")) != 1234:
        push_error("resume order or frame isolation broken: order=%s" % target.get("order"))
        return
    if int(target.get("acc1")) != 11 or int(target.get("acc2")) != 12:
        push_error("per-call frames did not isolate locals: acc1=%s acc2=%s" % [target.get("acc1"), target.get("acc2")])
        return
    if int(target.get("val1")) != 7 or int(target.get("val2")) != 7:
        push_error("both coroutines did not resume with the emitted value.")
        return

    print("__UNIT_TEST_PASS_MARKER__")

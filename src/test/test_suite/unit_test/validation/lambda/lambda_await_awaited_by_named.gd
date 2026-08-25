extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("start_run")
    if int(target.get("order")) != 12:
        push_error("named coroutine did not reach its dynamic await of the lambda state: order=%s" % target.get("order"))
        return
    if int(target.get("result")) != -1:
        push_error("named coroutine resumed before the lambda completed.")
        return

    target.call("emit_value", 21)
    if int(target.get("order")) != 1245:
        push_error("resume order broken across named/lambda boundary: order=%s" % target.get("order"))
        return
    if int(target.get("result")) != 42:
        push_error("lambda result did not cross the dynamic await into the named coroutine: result=%s" % target.get("result"))
        return

    print("__UNIT_TEST_PASS_MARKER__")

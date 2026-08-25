extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("start_run")
    if bool(target.get("done")):
        push_error("named coroutine did not suspend on the first signal.")
        return

    target.call("emit_value", 7)
    if bool(target.get("done")) or int(target.get("result")) != -1:
        push_error("lambda coroutine constructed after resume did not suspend on its own await.")
        return

    target.call("emit_value", 9)
    if not bool(target.get("done")):
        push_error("named coroutine did not resume after the post-resume lambda completed.")
        return
    if int(target.get("result")) != 709:
        push_error("capture copied from the resumed frame or lambda result broken: result=%s" % target.get("result"))
        return

    print("__UNIT_TEST_PASS_MARKER__")

extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var cb: Callable = target.call("make_cb")
    var state = cb.call()
    if state == null:
        push_error("coroutine lambda call did not return a state.")
        return

    cb = Callable()
    state = null
    target.call("emit_tick")
    if String(target.get("text_out")) != "hello" or int(target.get("size_out")) != 4:
        push_error("frame-held String/Array captures broken after Callable release: text=%s size=%s" % [target.get("text_out"), target.get("size_out")])
        return

    print("__UNIT_TEST_PASS_MARKER__")

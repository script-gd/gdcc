extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    # (a) Virtual _ready from the base _init: skipped apply, body forwarded once.
    # A manual _ready after construction is the first qualifying non-engine entry
    # and must apply the chain — proving the guarded window never latched DONE.
    target.call("run_virtual_case")
    var virtual_node: Node = target.get("virtual_node")
    var log: Array = target.call("get_log")
    if not log.is_empty():
        push_error("Constructor-window virtual _ready applied the chain: " + str(log))
        return
    if int(virtual_node.get("ready_calls")) != 1:
        push_error("Constructor-window virtual _ready did not forward the user body.")
        return
    target.call("manual_virtual")
    log = target.call("get_log")
    if log.size() != 2 or log[0] != "virtual_base" or log[1] != "virtual_sub":
        push_error("Post-construction manual _ready did not apply base then sub: " + str(log))
        return
    if int(virtual_node.get("base_value")) != 11 or int(virtual_node.get("sub_value")) != 22:
        push_error("Manual _ready did not restore the initializer values.")
        return
    if int(virtual_node.get("ready_calls")) != 2:
        push_error("Manual _ready did not forward the user body.")
        return
    target.call("attach_virtual")
    log = target.call("get_log")
    if log.size() != 4 or log[2] != "virtual_base" or log[3] != "virtual_sub":
        push_error("Engine READY did not replay base then sub: " + str(log))
        return
    if int(virtual_node.get("ready_calls")) != 3:
        push_error("Engine READY did not forward the user body once.")
        return

    # (b) Explicit NOTIFICATION_READY inside _init: composite impl fails closed,
    # DONE stays clear so the manual non-engine entry applies once before the
    # engine READY replay applies again.
    target.call("run_notify_case")
    var notify_node: Node = target.get("notify_node")
    log = target.call("get_log")
    if log.size() != 4:
        push_error("Constructor-window notification applied the chain: " + str(log))
        return
    if int(notify_node.get("ready_calls")) != 1:
        push_error("Guarded composite impl did not forward the user body.")
        return
    target.call("manual_notify")
    log = target.call("get_log")
    if log.size() != 5 or log[4] != "notify":
        push_error("Manual _ready did not apply the notify chain: " + str(log))
        return
    if int(notify_node.get("value")) != 33 or int(notify_node.get("ready_calls")) != 2:
        push_error("Manual _ready did not restore the value or forward the body.")
        return
    target.call("attach_notify")
    log = target.call("get_log")
    if log.size() != 6 or log[5] != "notify":
        push_error("Engine READY did not replay the notify chain: " + str(log))
        return
    if int(notify_node.get("value")) != 33 or int(notify_node.get("ready_calls")) != 3:
        push_error("Engine READY did not restore the value or forward the body.")
        return

    # (c) add_child into an already-ready parent inside _init: the single natural
    # READY is consumed in the window — no apply and no automatic re-dispatch.
    # Manual _ready then proves DONE was never latched; request_ready still
    # replays through the engine path afterwards.
    target.call("run_add_child_case")
    var add_child_node: Node = target.get("add_child_node")
    log = target.call("get_log")
    if log.size() != 6:
        push_error("add_child constructor-window READY applied the chain: " + str(log))
        return
    if int(add_child_node.get("value")) != 0:
        push_error("add_child case did not keep the constructor default value.")
        return
    if int(add_child_node.get("ready_calls")) != 1:
        push_error("add_child constructor-window READY did not forward the user body.")
        return
    target.call("manual_add_child")
    log = target.call("get_log")
    if log.size() != 7 or log[6] != "add_child":
        push_error("Manual _ready did not apply the add_child chain: " + str(log))
        return
    if int(add_child_node.get("value")) != 44 or int(add_child_node.get("ready_calls")) != 2:
        push_error("Manual _ready did not restore the value or forward the body.")
        return
    target.call("replay_add_child")
    log = target.call("get_log")
    if log.size() != 8 or log[7] != "add_child":
        push_error("request_ready replay did not apply the add_child chain: " + str(log))
        return
    if int(add_child_node.get("value")) != 44 or int(add_child_node.get("ready_calls")) != 3:
        push_error("request_ready replay did not restore the value or forward the body.")
        return
    print("__UNIT_TEST_PASS_MARKER__")

extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    # Super-entry-first leg: `await super._ready()` reaches the parent coroutine
    # thunk directly, so its entry initialization must apply the whole instance
    # chain before the parent body runs — the child thunk is never entered.
    target.call("create_child")
    target.call("child_call_super_manual")
    var child_node: Node = target.get("child")
    var log: Array = target.call("get_log")
    if log.size() != 2 or log[0] != "coro_parent" or log[1] != "coro_child":
        push_error("Super-entry thunk did not apply base-then-sub once: " + str(log))
        return
    if int(child_node.get("seen_value")) != 7 or int(child_node.get("suspended")) != 1:
        push_error("Parent coroutine did not observe the applied value before suspending.")
        return
    if int(child_node.get("child_seen")) != -1 or int(child_node.get("child_completed")) != 0:
        push_error("Child _ready thunk should not have run on the super-entry path.")
        return
    if int(child_node.get("completed")) != 0:
        push_error("Parent coroutine should still be suspended on proceed.")
        return

    target.call("emit_proceed")
    if int(child_node.get("completed")) != 1:
        push_error("Parent coroutine did not resume after proceed.")
        return

    # Direct internal call afterwards: the child thunk entry is deduped by the
    # latched DONE flag — the chain must not apply a second time.
    target.call("child_call_manual")
    log = target.call("get_log")
    if log.size() != 2:
        push_error("DONE did not dedup the manual coroutine _ready replay: " + str(log))
        return
    if int(child_node.get("child_seen")) != 9 or int(child_node.get("suspended")) != 2:
        push_error("Manual coroutine _ready did not forward the bodies.")
        return
    target.call("emit_proceed")
    if int(child_node.get("completed")) != 2 or int(child_node.get("child_completed")) != 1:
        push_error("Awaited super coroutine chain did not complete after proceed.")
        return

    # Engine READY path: composite impl replays the chain, forwards the shell and
    # the coroutines suspend again until the next emit.
    target.call("attach_child")
    log = target.call("get_log")
    if log.size() != 4 or log[2] != "coro_parent" or log[3] != "coro_child":
        push_error("Engine READY did not replay the coroutine chain: " + str(log))
        return
    if int(child_node.get("suspended")) != 3 or int(child_node.get("child_completed")) != 1:
        push_error("Engine READY did not forward the coroutine bodies.")
        return
    target.call("emit_proceed")
    if int(child_node.get("completed")) != 3 or int(child_node.get("child_completed")) != 2:
        push_error("Engine-driven coroutine run did not complete after proceed.")
        return
    print("__UNIT_TEST_PASS_MARKER__")

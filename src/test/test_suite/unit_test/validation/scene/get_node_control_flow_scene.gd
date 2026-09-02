# gdcc-test: output_not_contains=engine method call failed: Node.add_child
# gdcc-test: output_not_contains=engine method call failed: Node.get_node
extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var ok := true
    if not bool(target.call("if_route_ok")):
        ok = false
        push_error("Get-node if/elif route failed.")
    if not bool(target.call("ternary_route_ok")):
        ok = false
        push_error("Get-node ternary route failed.")
    if not bool(target.call("match_route_ok")):
        ok = false
        push_error("Get-node match route failed.")
    if not bool(target.call("loop_hits_ok")):
        ok = false
        push_error("Get-node for/while loop hits failed.")
    if not bool(target.call("nested_loop_hits_ok")):
        ok = false
        push_error("Get-node nested loop hits failed.")

    if ok:
        print("__UNIT_TEST_PASS_MARKER__")

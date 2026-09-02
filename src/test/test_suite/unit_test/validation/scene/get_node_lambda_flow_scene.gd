# gdcc-test: output_not_contains=engine method call failed: Node.add_child
# gdcc-test: output_not_contains=engine method call failed: Node.get_node
extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var ok := true
    if not bool(target.call("lambda_if_route_ok")):
        ok = false
        push_error("Get-node lambda if/elif route failed.")
    if not bool(target.call("lambda_match_route_ok")):
        ok = false
        push_error("Get-node lambda match route failed.")
    if not bool(target.call("lambda_loop_hits_ok")):
        ok = false
        push_error("Get-node lambda loop hits failed.")
    if not bool(target.call("branch_lambda_routes_ok")):
        ok = false
        push_error("Branch-created get-node lambdas failed.")
    if not bool(target.call("loop_captured_lambdas_ok")):
        ok = false
        push_error("Loop-captured get-node lambdas failed.")

    var outer: Callable = target.call("make_nested_cb")
    if outer == null or not outer.is_valid() or outer.get_object() != target:
        ok = false
        push_error("Nested get-node lambda: outer callable invalid or lost its object identity.")
    else:
        var inner = outer.call()
        if inner == null or not (inner is Callable):
            ok = false
            push_error("Nested get-node lambda: inner callable missing.")
        else:
            var pair = inner.call()
            if not (pair is Array) or pair.size() != 2 or pair[0] != &"Child" or pair[1] != &"Unique":
                ok = false
                push_error("Nested get-node lambda resolved wrong nodes.")

    if ok:
        print("__UNIT_TEST_PASS_MARKER__")

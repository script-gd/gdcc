extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("run_case")
    var first_res_id := int(target.call("get_res_instance_id"))
    if int(target.call("get_apply_count")) != 1 or int(target.call("get_value")) != 33:
        push_error("Initial engine READY did not apply the onready chain once.")
        return

    # Re-entry without request_ready must not replay: keep the externally written
    # value and the same RefCounted instance.
    target.call("overwrite_value", 99)
    target.call("remove_child_node")
    target.call("reattach_child")
    if int(target.call("get_apply_count")) != 1:
        push_error("Re-entry without request_ready replayed the onready chain.")
        return
    if int(target.call("get_value")) != 99:
        push_error("Re-entry without request_ready overwrote the stored value.")
        return
    if int(target.call("get_res_instance_id")) != first_res_id:
        push_error("Re-entry without request_ready replaced the RefCounted value.")
        return

    # request_ready re-arms the engine dispatch: the chain replays, restoring the
    # initializer value and releasing the previous RefCounted instance.
    target.call("remove_child_node")
    target.call("arm_request_ready")
    target.call("reattach_child")
    if int(target.call("get_apply_count")) != 2:
        push_error("request_ready re-entry did not replay the onready chain.")
        return
    if int(target.call("get_value")) != 33:
        push_error("request_ready replay did not restore the initializer value.")
        return
    var replay_res_id := int(target.call("get_res_instance_id"))
    if replay_res_id == first_res_id:
        push_error("request_ready replay did not apply a fresh RefCounted value.")
        return
    if is_instance_id_valid(first_res_id):
        push_error("request_ready replay leaked the previous RefCounted value.")
        return
    print("__UNIT_TEST_PASS_MARKER__")

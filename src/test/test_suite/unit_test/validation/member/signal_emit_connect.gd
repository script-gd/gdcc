extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var connect_err = int(target.call("connect_default"))
    target.call("emit_none")
    target.call("emit_declared", 7)
    target.call("emit_heterogeneous", "mixed", Vector2(1, 2))
    var counted_value = int(target.call("fire_counted", 9))
    var hits_before_disconnect = int(target.call("read_hits"))
    var mixed_int = int(target.call("read_mixed_int"))
    var mixed_label = String(target.call("read_mixed_label"))
    var mixed_vec = target.call("read_mixed_vec")
    if connect_err != 0 or hits_before_disconnect != 3 or counted_value != 9 \
            or mixed_int != 1 or mixed_label != "mixed" or mixed_vec != Vector2(1, 2):
        push_error("Signal emit/connect default path failed: err=%s hits=%s counted=%s" % [
            connect_err,
            hits_before_disconnect,
            counted_value
        ])
        return

    target.call("disconnect_default")
    var hits_after_disconnect = int(target.call("fire_and_count"))
    if hits_after_disconnect != hits_before_disconnect:
        push_error("Signal disconnect still delivered emit: before=%s after=%s" % [
            hits_before_disconnect,
            hits_after_disconnect
        ])
        return

    var one_shot_err = int(target.call("connect_one_shot"))
    var after_first = int(target.call("fire_one_shot"))
    var after_second = int(target.call("fire_one_shot"))
    if one_shot_err != 0 or after_first != 1 or after_second != 1:
        push_error("CONNECT_ONE_SHOT failed: err=%s first=%s second=%s" % [
            one_shot_err,
            after_first,
            after_second
        ])
        return

    var deferred_err = int(target.call("connect_deferred"))
    if deferred_err != 0:
        push_error("CONNECT_DEFERRED connect failed: %s" % deferred_err)
        return
    var before_idle = int(target.call("fire_deferred"))
    if before_idle != 0:
        push_error("CONNECT_DEFERRED fired synchronously: %s" % before_idle)
        return
    # process_frame is emitted before MessageQueue flush; wait a second idle frame
    # so CONNECT_DEFERRED is delivered before we read the hit count.
    await get_tree().process_frame
    await get_tree().process_frame
    if int(target.call("read_deferred_hits")) != 1:
        push_error("CONNECT_DEFERRED did not fire after idle: %s" % int(target.call("read_deferred_hits")))
        return

    print("__UNIT_TEST_PASS_MARKER__")

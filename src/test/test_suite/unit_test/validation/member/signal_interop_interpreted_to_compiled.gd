extends Node

class InterpretedEmitter extends Node:
    signal pinged
    signal counted(value: int)
    signal callable_pinged

var inbound_hits: int = 0

func _on_inbound() -> void:
    inbound_hits += 1

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var host = InterpretedEmitter.new()
    add_child(host)

    var pinged_err = int(target.call("wire_pinged", host.pinged))
    var counted_err = int(target.call("wire_counted", host.counted))
    if pinged_err != 0 or counted_err != 0:
        push_error("Compiled connect to interpreted Signal failed: pinged=%s counted=%s" % [
            pinged_err,
            counted_err
        ])
        return

    host.pinged.emit()
    host.counted.emit(9)
    if int(target.call("read_pinged_hits")) != 1 or int(target.call("read_last_count")) != 9:
        push_error("Interpreted emit did not reach compiled Signal-parameter handler: hits=%s counted=%s" % [
            int(target.call("read_pinged_hits")),
            int(target.call("read_last_count"))
        ])
        return

    var handler = target.call("get_callable_handler")
    if typeof(handler) != TYPE_CALLABLE:
        push_error("Compiled handler Callable was not returned: %s" % typeof(handler))
        return
    var callable_err = host.callable_pinged.connect(handler)
    if callable_err != 0:
        push_error("Interpreted connect of compiled Callable failed: %s" % callable_err)
        return
    host.callable_pinged.emit()
    if int(target.call("read_callable_hits")) != 1:
        push_error("Interpreted emit did not reach compiled returned-Callable handler: %s" % int(target.call("read_callable_hits")))
        return

    var inbound_err = int(target.call("connect_inbound_callable", _on_inbound))
    if inbound_err != 0:
        push_error("Compiled connect of interpreted Callable failed: %s" % inbound_err)
        return
    target.call("fire_inbound_callable")
    if inbound_hits != 1:
        push_error("Compiled emit did not reach interpreted inbound Callable: %s" % inbound_hits)
        return

    print("__UNIT_TEST_PASS_MARKER__")

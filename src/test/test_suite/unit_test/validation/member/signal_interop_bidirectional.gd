extends Node

class InterpretedPeer extends Node:
    signal interpreted_pinged
    signal interpreted_counted(value: int)

var from_compiled_hits: int = 0
var last_compiled_count: int = 0

func _on_from_compiled() -> void:
    from_compiled_hits += 1

func _on_from_compiled_counted(value: int) -> void:
    last_compiled_count = value

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var peer = InterpretedPeer.new()
    add_child(peer)

    var compiled_err = target.compiled_pinged.connect(_on_from_compiled)
    var compiled_counted_err = target.compiled_counted.connect(_on_from_compiled_counted)
    var interpreted_err = int(target.call("wire_interpreted", peer.interpreted_pinged))
    var interpreted_counted_err = int(target.call("wire_interpreted_counted", peer.interpreted_counted))
    if compiled_err != 0 or compiled_counted_err != 0 \
            or interpreted_err != 0 or interpreted_counted_err != 0:
        push_error("Bidirectional wiring failed: compiled=%s/%s interpreted=%s/%s" % [
            compiled_err,
            compiled_counted_err,
            interpreted_err,
            interpreted_counted_err
        ])
        return

    target.call("fire_compiled")
    target.call("fire_compiled_counted", 11)
    peer.interpreted_pinged.emit()
    peer.interpreted_counted.emit(13)

    if from_compiled_hits != 1 or last_compiled_count != 11:
        push_error("Compiled emit did not reach interpreted peer: hits=%s counted=%s" % [
            from_compiled_hits,
            last_compiled_count
        ])
        return
    if int(target.call("read_from_interpreted_hits")) != 1 \
            or int(target.call("read_last_interpreted_count")) != 13:
        push_error("Interpreted emit did not reach compiled peer: hits=%s counted=%s" % [
            int(target.call("read_from_interpreted_hits")),
            int(target.call("read_last_interpreted_count"))
        ])
        return

    print("__UNIT_TEST_PASS_MARKER__")

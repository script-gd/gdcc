extends Node

var pinged_hits: int = 0
var last_count: int = 0
var one_shot_hits: int = 0
var deferred_hits: int = 0

func _on_pinged() -> void:
    pinged_hits += 1

func _on_counted(value: int) -> void:
    last_count = value

func _on_one_shot() -> void:
    one_shot_hits += 1

func _on_deferred() -> void:
    deferred_hits += 1

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var pinged_err = target.pinged.connect(_on_pinged)
    var counted_err = target.counted.connect(_on_counted)
    if pinged_err != 0 or counted_err != 0:
        push_error("Interpreted connect to compiled signal failed: pinged=%s counted=%s" % [
            pinged_err,
            counted_err
        ])
        return
    if not target.pinged.is_connected(_on_pinged) or not target.counted.is_connected(_on_counted):
        push_error("Interpreted is_connected missed compiled signal wiring.")
        return

    target.call("fire_pinged")
    target.call("fire_counted", 42)
    if pinged_hits != 1 or last_count != 42:
        push_error("Compiled emit did not reach interpreted handler: hits=%s counted=%s" % [
            pinged_hits,
            last_count
        ])
        return

    target.pinged.disconnect(_on_pinged)
    target.call("fire_pinged")
    if pinged_hits != 1:
        push_error("Interpreted disconnect still delivered compiled emit: %s" % pinged_hits)
        return

    var one_shot_err = target.one_shot_pinged.connect(_on_one_shot, Object.CONNECT_ONE_SHOT)
    if one_shot_err != 0:
        push_error("Interpreted CONNECT_ONE_SHOT connect failed: %s" % one_shot_err)
        return
    target.call("fire_one_shot")
    target.call("fire_one_shot")
    if one_shot_hits != 1:
        push_error("Interpreted CONNECT_ONE_SHOT failed: %s" % one_shot_hits)
        return

    var deferred_err = target.deferred_pinged.connect(_on_deferred, Object.CONNECT_DEFERRED)
    if deferred_err != 0:
        push_error("Interpreted CONNECT_DEFERRED connect failed: %s" % deferred_err)
        return
    target.call("fire_deferred")
    if deferred_hits != 0:
        push_error("Interpreted CONNECT_DEFERRED fired synchronously: %s" % deferred_hits)
        return
    # process_frame is emitted before MessageQueue flush; wait a second idle frame
    # so CONNECT_DEFERRED is delivered before we read the hit count.
    await get_tree().process_frame
    await get_tree().process_frame
    if deferred_hits != 1:
        push_error("Interpreted CONNECT_DEFERRED did not fire after idle: %s" % deferred_hits)
        return

    print("__UNIT_TEST_PASS_MARKER__")

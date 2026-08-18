extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var local_value = int(target.call("local_int_capture"))
    var param_value = int(target.call("param_capture", 5))
    var label = String(target.call("string_capture", "hello"))
    var multi_value = int(target.call("multi_capture", 2, 7))
    var copy_value = int(target.call("copy_on_capture"))
    var shared_size = int(target.call("shared_array_identity"))
    var iterator_sum = int(target.call("for_iterator_capture"))
    var iterator_snapshot = int(target.call("for_iterator_snapshot"))
    var shadowed = int(target.call("shadow_own_parameter"))

    if local_value != 42:
        push_error("local int capture failed: %s" % local_value)
        return
    if param_value != 8:
        push_error("param capture failed: %s" % param_value)
        return
    if label != "hello":
        push_error("string capture failed: %s" % label)
        return
    if multi_value != 207:
        push_error("multi capture failed: %s" % multi_value)
        return
    if copy_value != 10099:
        push_error("copy-on-capture failed: %s" % copy_value)
        return
    if shared_size != 2:
        push_error("shared array identity failed: %s" % shared_size)
        return
    if iterator_sum != 3:
        push_error("for-iterator capture failed: %s" % iterator_sum)
        return
    if iterator_snapshot != 12:
        push_error("for-iterator delayed snapshot failed: %s" % iterator_snapshot)
        return
    if shadowed != 3:
        push_error("shadowed lambda parameter failed: %s" % shadowed)
        return

    print("__UNIT_TEST_PASS_MARKER__")

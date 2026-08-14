extends Node

class InterpretedSignalHost extends Node:
    signal custom_pinged

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var own = target.call("read_variant_pinged", target)
    var engine_ready = target.call("read_variant_ready", target)
    if typeof(own) != TYPE_SIGNAL or String(own.get_name()) != "pinged":
        push_error("Variant named-get missed ClassDB-registered compiled signal: type=%s name=%s" % [
            typeof(own),
            own.get_name() if typeof(own) == TYPE_SIGNAL else own
        ])
        return
    if typeof(engine_ready) != TYPE_SIGNAL or String(engine_ready.get_name()) != "ready":
        push_error("Variant named-get missed engine Node.ready: type=%s name=%s" % [
            typeof(engine_ready),
            engine_ready.get_name() if typeof(engine_ready) == TYPE_SIGNAL else engine_ready
        ])
        return
    if int(own.get_object_id()) != int(target.get_instance_id()) \
            or int(engine_ready.get_object_id()) != int(target.get_instance_id()):
        push_error("Variant named-get Signal identity drifted: own=%s ready=%s target=%s" % [
            own.get_object_id(),
            engine_ready.get_object_id(),
            target.get_instance_id()
        ])
        return

    var host = InterpretedSignalHost.new()
    add_child(host)
    var interpreted_attr = host.custom_pinged
    var interpreted_get = host.get("custom_pinged")
    var compiled_custom = target.call("read_variant_custom", host)
    if typeof(interpreted_attr) != TYPE_SIGNAL or String(interpreted_attr.get_name()) != "custom_pinged":
        push_error("Interpreted attribute read of custom signal failed: type=%s" % typeof(interpreted_attr))
        return
    if typeof(compiled_custom) != typeof(interpreted_get):
        push_error("Compiled Variant named-get diverged from Object.get for interpreted custom signal: compiled=%s get=%s attr=%s" % [
            typeof(compiled_custom),
            typeof(interpreted_get),
            typeof(interpreted_attr)
        ])
        return
    if typeof(interpreted_get) == TYPE_SIGNAL:
        if typeof(compiled_custom) != TYPE_SIGNAL \
                or String(compiled_custom.get_name()) != "custom_pinged" \
                or int(compiled_custom.get_object_id()) != int(host.get_instance_id()):
            push_error("Compiled Variant named-get did not reconstruct interpreted custom Signal: type=%s name=%s id=%s" % [
                typeof(compiled_custom),
                compiled_custom.get_name() if typeof(compiled_custom) == TYPE_SIGNAL else compiled_custom,
                compiled_custom.get_object_id() if typeof(compiled_custom) == TYPE_SIGNAL else -1
            ])
            return
    elif compiled_custom != null and compiled_custom != interpreted_get:
        push_error("Compiled Variant named-get returned unexpected non-Signal for interpreted custom signal: %s" % compiled_custom)
        return

    print("__UNIT_TEST_PASS_MARKER__")

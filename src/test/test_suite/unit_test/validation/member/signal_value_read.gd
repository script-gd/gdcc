extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var bare = target.call("copy_bare")
    var from_self = target.call("copy_self")
    var from_other = target.call("copy_other", target)
    var runtime_class = String(target.get_class())
    if typeof(bare) == TYPE_SIGNAL \
            and typeof(from_self) == TYPE_SIGNAL \
            and typeof(from_other) == TYPE_SIGNAL \
            and runtime_class == "SignalValueReadSmoke":
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("SignalValueReadSmoke validation failed: bare=%s, self=%s, other=%s, class=%s" % [
            typeof(bare),
            typeof(from_self),
            typeof(from_other),
            runtime_class
        ])

extends Node

var ready_hits: int = 0
var child_hits: int = 0

func _on_compiled_ready() -> void:
    ready_hits += 1

func _on_child_entered(_node: Node) -> void:
    child_hits += 1

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var button := Button.new()
    add_child(button)
    var button_err = int(target.call("wire_button", button))
    if button_err != 0:
        push_error("Compiled connect to interpreted engine Button.pressed failed: %s" % button_err)
        return
    button.pressed.emit()
    if int(target.call("read_button_hits")) != 1:
        push_error("Interpreted engine emit did not reach compiled handler: %s" % int(target.call("read_button_hits")))
        return

    var ready_err = target.ready.connect(_on_compiled_ready)
    if ready_err != 0:
        push_error("Interpreted connect to compiled Node.ready failed: %s" % ready_err)
        return
    target.call("fire_ready")
    if ready_hits != 1:
        push_error("Compiled engine-signal emit did not reach interpreted handler: %s" % ready_hits)
        return

    var child_err = target.child_entered_tree.connect(_on_child_entered)
    if child_err != 0:
        push_error("Interpreted connect to compiled child_entered_tree failed: %s" % child_err)
        return
    var child := Node.new()
    target.add_child(child)
    if child_hits != 1:
        push_error("Engine child_entered_tree did not reach interpreted handler: %s" % child_hits)
        return

    print("__UNIT_TEST_PASS_MARKER__")

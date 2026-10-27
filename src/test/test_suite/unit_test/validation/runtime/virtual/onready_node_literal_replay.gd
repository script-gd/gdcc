# gdcc-test: output_not_contains=ERROR
# gdcc-test: output_not_contains=engine method call failed
# gdcc-test: output_not_contains=assert_object_live failed
# gdcc-test: output_not_contains=Node not found
extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var probe = target.call("create_probe")
    if probe == null:
        push_error("Failed to construct replay probe.")
        return
    if probe.child_node != null or probe.unique_node != null:
        push_error("Onready lookup fields should stay default before tree entry.")
        return

    var first_child = Node.new()
    first_child.name = "Child"
    probe.add_child(first_child)
    var first_unique = Node.new()
    first_unique.name = "Unique"
    probe.add_child(first_unique)
    first_unique.owner = probe
    first_unique.unique_name_in_owner = true

    add_child(probe)
    if probe.child_node != first_child or probe.unique_node != first_unique:
        push_error("Initial READY did not resolve the node literal lookups.")
        return

    # Swap the children at the same paths; the detached old nodes stay alive so
    # the fields can keep pointing at them while no replay is armed.
    remove_child(probe)
    probe.remove_child(first_child)
    first_unique.unique_name_in_owner = false
    probe.remove_child(first_unique)
    var second_child = Node.new()
    second_child.name = "Child"
    probe.add_child(second_child)
    var second_unique = Node.new()
    second_unique.name = "Unique"
    probe.add_child(second_unique)
    second_unique.owner = probe
    second_unique.unique_name_in_owner = true

    add_child(probe)
    if probe.child_node != first_child or probe.unique_node != first_unique:
        push_error("Re-entry without request_ready re-evaluated the lookups.")
        return

    remove_child(probe)
    probe.request_ready()
    add_child(probe)
    if probe.child_node != second_child or probe.unique_node != second_unique:
        push_error("request_ready replay did not re-evaluate the lookups.")
        return

    probe.free()
    first_child.free()
    first_unique.free()
    print("__UNIT_TEST_PASS_MARKER__")

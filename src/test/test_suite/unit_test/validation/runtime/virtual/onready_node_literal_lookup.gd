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
        push_error("Failed to construct lookup probe.")
        return
    if probe.child_node != null or probe.untyped_child != null \
            or String(probe.child_name) != "" \
            or not probe.get_name_callable.is_null() or not probe.renamed_signal.is_null():
        push_error("Onready fields should keep declared defaults before tree entry.")
        return

    # Children are created only after the probe exists; they must still be found
    # when the real READY dispatch applies the initializers.
    probe.name = "ProbeName"
    var child = Node.new()
    child.name = "Child"
    probe.add_child(child)
    var branch = Node.new()
    branch.name = "Branch"
    probe.add_child(branch)
    var leaf = Node.new()
    leaf.name = "Leaf"
    branch.add_child(leaf)
    var spaced = Node.new()
    spaced.name = "Name With Space"
    probe.add_child(spaced)
    var unique = Node.new()
    unique.name = "Unique"
    probe.add_child(unique)
    unique.owner = probe
    unique.unique_name_in_owner = true
    var spaced_unique = Node.new()
    spaced_unique.name = "Unique Name"
    probe.add_child(spaced_unique)
    spaced_unique.owner = probe
    spaced_unique.unique_name_in_owner = true

    add_child(probe)
    if probe.child_node != child or probe.nested_leaf != leaf or probe.quoted_node != spaced \
            or probe.unique_node != unique or probe.spaced_unique_node != spaced_unique \
            or probe.untyped_child != child:
        push_error("Node literal lookup resolved to the wrong node.")
        return
    if probe.child_name != child.name or probe.unique_name != StringName("Unique"):
        push_error("Suffix route values did not match the runtime members.")
        return
    if probe.get_name_callable.get_object() != child \
            or probe.get_name_callable.get_method() != StringName("get_name") \
            or probe.get_name_callable.call() != child.name:
        push_error("Callable suffix did not bind the runtime get_name method.")
        return
    if probe.renamed_signal.get_object() != child \
            or probe.renamed_signal.get_name() != StringName("renamed"):
        push_error("Signal suffix did not bind the runtime renamed signal.")
        return
    if probe.self_name != probe.name or probe.parent_name != probe.name:
        push_error("Indirect-self lookups did not resolve the probe name.")
        return
    if probe.ready_child != child or probe.ready_name != child.name:
        push_error("User _ready did not observe initialized onready fields.")
        return

    var plain = target.call("create_plain")
    var plain_child = Node.new()
    plain_child.name = "Child"
    plain.add_child(plain_child)
    add_child(plain)
    if plain.child_node != plain_child:
        push_error("Class without user _ready did not apply the onready lookup.")
        return

    var sub = target.call("create_sub")
    var sub_child = Node.new()
    sub_child.name = "Child"
    sub.add_child(sub_child)
    var sub_leaf = Node.new()
    sub_leaf.name = "Leaf"
    sub_child.add_child(sub_leaf)
    add_child(sub)
    if sub.base_child != sub_child or sub.sub_leaf != sub_leaf:
        push_error("Inherited onready lookup resolved to the wrong nodes.")
        return
    if sub.observed_base != sub_child or sub.observed_leaf != sub_leaf:
        push_error("Final user _ready did not observe the inherited fields.")
        return
    if sub.base_ready_calls != 1 or sub.sub_ready_calls != 1:
        push_error("super._ready() replayed or skipped the user ready bodies.")
        return
    if int(target.call("get_stamp_applies")) != 2 or sub.base_stamp != 1 or sub.sub_stamp != 2:
        push_error("Onready chain did not apply parent-before-child exactly once.")
        return

    probe.free()
    plain.free()
    sub.free()
    print("__UNIT_TEST_PASS_MARKER__")

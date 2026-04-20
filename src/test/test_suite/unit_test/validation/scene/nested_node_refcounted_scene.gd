# gdcc-test: output_not_contains=engine method call failed: Node.add_child
# gdcc-test: output_not_contains=engine method call failed: Node.get_node_or_null
extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var ok = bool(target.call("inner_scene_interop_ok"))
    if ok:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("NestedNodeRefcountedSceneSmoke validation failed.")

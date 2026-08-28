extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var result = target.call("mutate", "b")
    var ok: bool = result is Array and result.size() == 9
    if ok:
        # Initializers ran ([1, 1]), the object slot defaulted to null (true), the mutations
        # landed ([2, 2, true]), and a second instance sees the same shared storage ([2, 2, true]).
        var expected := [1, 1, true, 2, 2, true, 2, 2, true]
        for i in expected.size():
            if result[i] != expected[i]:
                ok = false
    if ok:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Static var destroyable validation failed: %s" % [result])

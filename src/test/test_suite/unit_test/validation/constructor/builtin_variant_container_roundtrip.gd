extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var array_payload := [Node.new(), 7, "leaf"]
    var reopened_array: Array = target.call("reopen_array", array_payload)
    var dictionary_payload := {"hp": 7, "name": "gdcc"}
    var reopened_dictionary: Dictionary = target.call("reopen_dictionary", dictionary_payload)
    var copied_array_size = int(target.call("copy_array", array_payload))
    var copied_dictionary_size = int(target.call("copy_dictionary", dictionary_payload))

    if (
        not reopened_array.is_typed()
        and reopened_array.size() == 3
        and reopened_array[0] is Node
        and int(reopened_array[1]) == 7
        and String(reopened_array[2]) == "leaf"
        and reopened_dictionary.size() == 2
        and int(reopened_dictionary["hp"]) == 7
        and String(reopened_dictionary["name"]) == "gdcc"
        and copied_array_size == 3
        and copied_dictionary_size == 2
    ):
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error(
            "Builtin Variant container constructor validation failed: array_size=%d dict_size=%d copied_array_size=%d copied_dict_size=%d"
            % [reopened_array.size(), reopened_dictionary.size(), copied_array_size, copied_dictionary_size]
        )

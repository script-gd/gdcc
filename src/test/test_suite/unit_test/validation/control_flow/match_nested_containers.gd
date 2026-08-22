extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var ok := true
    if int(target.call("empty_array")) != 1:
        push_error("empty_array: expected 1, got %d" % int(target.call("empty_array")))
        ok = false
    if int(target.call("specific_array")) != 2:
        push_error("specific_array: expected 2, got %d" % int(target.call("specific_array")))
        ok = false
    if int(target.call("bind_then_wildcard_array")) != 17:
        push_error("bind_then_wildcard_array: expected 17, got %d" % int(target.call("bind_then_wildcard_array")))
        ok = false
    if int(target.call("open_ended_array")) != 3:
        push_error("open_ended_array: expected 3, got %d" % int(target.call("open_ended_array")))
        ok = false
    if int(target.call("array_fallback")) != 0:
        push_error("array_fallback: expected 0, got %d" % int(target.call("array_fallback")))
        ok = false
    if int(target.call("empty_dict")) != 1:
        push_error("empty_dict: expected 1, got %d" % int(target.call("empty_dict")))
        ok = false
    if int(target.call("exact_name_dict")) != 2:
        push_error("exact_name_dict: expected 2, got %d" % int(target.call("exact_name_dict")))
        ok = false
    if int(target.call("name_and_age_dict")) != 14:
        push_error("name_and_age_dict: expected 14, got %d" % int(target.call("name_and_age_dict")))
        ok = false
    if int(target.call("key_presence_dict")) != 4:
        push_error("key_presence_dict: expected 4, got %d" % int(target.call("key_presence_dict")))
        ok = false
    if int(target.call("open_ended_dict")) != 5:
        push_error("open_ended_dict: expected 5, got %d" % int(target.call("open_ended_dict")))
        ok = false
    if int(target.call("dict_fallback")) != 0:
        push_error("dict_fallback: expected 0, got %d" % int(target.call("dict_fallback")))
        ok = false
    if int(target.call("nested_array_hit")) != 123:
        push_error("nested_array_hit: expected 123, got %d" % int(target.call("nested_array_hit")))
        ok = false
    if int(target.call("nested_array_miss")) != 0:
        push_error("nested_array_miss: expected 0, got %d" % int(target.call("nested_array_miss")))
        ok = false
    if int(target.call("array_of_dict_hit")) != 7:
        push_error("array_of_dict_hit: expected 7, got %d" % int(target.call("array_of_dict_hit")))
        ok = false
    if int(target.call("dict_of_array_hit")) != 9:
        push_error("dict_of_array_hit: expected 9, got %d" % int(target.call("dict_of_array_hit")))
        ok = false

    if ok:
        print("__UNIT_TEST_PASS_MARKER__")

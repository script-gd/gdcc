extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var ok := true
    if int(target.call("text_go")) != 1:
        push_error("text_go: expected 1, got %d" % int(target.call("text_go")))
        ok = false
    if int(target.call("text_stop")) != 2:
        push_error("text_stop: expected 2, got %d" % int(target.call("text_stop")))
        ok = false
    if int(target.call("text_halt")) != 2:
        push_error("text_halt: expected 2, got %d" % int(target.call("text_halt")))
        ok = false
    if int(target.call("text_other")) != 0:
        push_error("text_other: expected 0, got %d" % int(target.call("text_other")))
        ok = false
    if int(target.call("name_go")) != 1:
        push_error("name_go: expected 1, got %d" % int(target.call("name_go")))
        ok = false
    if int(target.call("name_stop")) != 2:
        push_error("name_stop: expected 2, got %d" % int(target.call("name_stop")))
        ok = false
    if int(target.call("name_other")) != 0:
        push_error("name_other: expected 0, got %d" % int(target.call("name_other")))
        ok = false
    if int(target.call("crossover_string_hit")) != 1:
        push_error("crossover_string_hit: expected 1, got %d" % int(target.call("crossover_string_hit")))
        ok = false
    if int(target.call("crossover_string_miss")) != 0:
        push_error("crossover_string_miss: expected 0, got %d" % int(target.call("crossover_string_miss")))
        ok = false
    if int(target.call("crossover_name_hit")) != 1:
        push_error("crossover_name_hit: expected 1, got %d" % int(target.call("crossover_name_hit")))
        ok = false
    if int(target.call("crossover_name_miss")) != 0:
        push_error("crossover_name_miss: expected 0, got %d" % int(target.call("crossover_name_miss")))
        ok = false

    if ok:
        print("__UNIT_TEST_PASS_MARKER__")

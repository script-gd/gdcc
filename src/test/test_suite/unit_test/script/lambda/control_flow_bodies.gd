class_name LambdaControlFlowBodiesSmoke
extends Node

var ctor_cb: Callable

func _init() -> void:
    var seed := 41
    ctor_cb = func() -> int:
        return seed

func constructor_capture() -> int:
    return int(ctor_cb.call())

func in_if_true() -> int:
    var cb := func() -> int:
        return -1
    if true:
        cb = func() -> int:
            return 1
    else:
        cb = func() -> int:
            return 2
    return int(cb.call())

func in_if_false() -> int:
    var cb := func() -> int:
        return -1
    if false:
        cb = func() -> int:
            return 1
    else:
        cb = func() -> int:
            return 2
    return int(cb.call())

func in_while() -> int:
    var n := 0
    var total := 0
    while n < 3:
        var cb := func() -> int:
            return 1
        total += int(cb.call())
        n += 1
    return total

func in_for_body() -> int:
    var total := 0
    for i in range(3):
        var cb := func() -> int:
            return 4
        total += int(cb.call())
    return total

func in_for_capture_iterator() -> int:
    var total := 0
    for item in range(1, 4):
        var cb := func() -> int:
            return item
        total += int(cb.call())
    return total

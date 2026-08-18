class_name LambdaCapturesSmoke
extends Node

func local_int_capture() -> int:
    var seed := 40
    var cb := func() -> int:
        return seed + 2
    return int(cb.call())

func param_capture(seed: int) -> int:
    var cb := func() -> int:
        return seed + 3
    return int(cb.call())

func string_capture(label: String) -> String:
    var cb := func() -> String:
        return label
    return String(cb.call())

func multi_capture(left: int, right: int) -> int:
    var cb := func() -> int:
        return left * 100 + right
    return int(cb.call())

func copy_on_capture() -> int:
    var seed := 10
    var cb := func() -> int:
        seed = 99
        return seed
    var inner := int(cb.call())
    return seed * 1000 + inner

func shared_array_identity() -> int:
    var values: Array = [1]
    var cb := func() -> Array:
        return values
    var inner: Array = cb.call()
    inner.push_back(2)
    return values.size()

func for_iterator_capture() -> int:
    var total := 0
    for item in range(3):
        var cb := func() -> int:
            return item
        total += int(cb.call())
    return total

func for_iterator_snapshot() -> int:
    var cbs: Array = []
    for item in range(3):
        cbs.push_back(func() -> int:
            return item
        )
    return int(cbs[0].call()) * 100 + int(cbs[1].call()) * 10 + int(cbs[2].call())

func shadow_own_parameter() -> int:
    var seed := 10
    var cb := func(seed: int) -> int:
        return seed
    return int(cb.call(3))

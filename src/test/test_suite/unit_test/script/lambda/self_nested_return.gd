class_name LambdaSelfNestedReturnSmoke
extends Node

var hits: int = 0

func bump() -> void:
    hits += 1

func implicit_self_member() -> int:
    hits = 0
    var cb := func() -> void:
        hits += 1
    cb.call()
    return hits

func implicit_self_method() -> int:
    hits = 0
    var cb := func() -> void:
        bump()
    cb.call()
    return hits

func explicit_self() -> int:
    hits = 4
    var cb := func() -> int:
        return self.hits
    return int(cb.call())

func self_get_object_is_target() -> bool:
    var cb := func() -> int:
        return hits
    return cb.get_object() == self

func return_lambda() -> Callable:
    return func(n: int) -> int:
        return n + 1

func call_returned(value: int) -> int:
    var cb := return_lambda()
    return int(cb.call(value))

func apply(cb: Callable, value: int) -> int:
    return int(cb.call(value))

func pass_as_argument(value: int) -> int:
    return apply(func(n: int) -> int:
        return n * 3
    , value)

func nested_capture() -> int:
    var seed := 40
    var outer := func():
        var inner := func():
            return seed + 2
        return inner
    var inner_cb: Callable = outer.call()
    return int(inner_cb.call())

class_name LambdaValueCallAndAritySmoke
extends Node

var stored: Callable

func captureless_constant() -> int:
    var cb := func() -> int:
        return 7
    return int(cb.call())

func captureless_argument_count() -> int:
    var cb := func() -> int:
        return 7
    return int(cb.get_argument_count())

func captured_argument_count() -> int:
    var seed := 1
    var cb := func() -> int:
        return seed
    return int(cb.get_argument_count())

func typed_param_argument_count() -> int:
    var cb := func(n: int) -> int:
        return n
    return int(cb.get_argument_count())

func typed_param_and_return(value: int) -> int:
    var cb := func(n: int) -> int:
        return n + 1
    return int(cb.call(value))

func omitted_return_type(value: int) -> int:
    var cb := func(n: int):
        return n * 2
    return int(cb.call(value))

func untyped_param(value: Variant) -> Variant:
    var cb := func(item):
        return item
    return cb.call(value)

func stored_member_call() -> int:
    stored = func() -> int:
        return 8
    return int(stored.call())

func captureless_is_valid() -> bool:
    var cb := func() -> int:
        return 1
    return cb.is_valid()

func captureless_get_object_is_null() -> bool:
    var cb := func() -> int:
        return 1
    return cb.get_object() == null

func distinct_identity() -> bool:
    var first := func() -> int:
        return 1
    var second := func() -> int:
        return 1
    return first != second

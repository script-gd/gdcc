class_name MatchExpressionSmoke
extends Node

func helper() -> int:
    return 7

func by_identifier(value: int, other: int) -> int:
    match value:
        other:
            return 1
        _:
            return 0

func by_call(value: int) -> int:
    match value:
        helper():
            return 1
        helper() + 1:
            return 2
        _:
            return 0

func by_typeof(value: Variant) -> int:
    match typeof(value):
        TYPE_INT:
            return 1
        TYPE_FLOAT:
            return 2
        TYPE_STRING:
            return 3
        TYPE_ARRAY:
            return 4
        _:
            return 0

func identifier_hit() -> int:
    return by_identifier(4, 4)

func identifier_miss() -> int:
    return by_identifier(4, 5)

func call_hit() -> int:
    return by_call(7)

func call_plus_one_hit() -> int:
    return by_call(8)

func call_miss() -> int:
    return by_call(1)

func typeof_int() -> int:
    return by_typeof(3)

func typeof_float() -> int:
    return by_typeof(1.5)

func typeof_string() -> int:
    return by_typeof("x")

func typeof_array() -> int:
    return by_typeof([1])

func typeof_other() -> int:
    return by_typeof(true)

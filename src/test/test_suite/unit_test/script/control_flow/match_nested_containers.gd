class_name MatchNestedContainersSmoke
extends Node

func describe_array(value) -> int:
    match value:
        []:
            return 1
        [1, 3, "test", null]:
            return 2
        [var start, _, "test"]:
            return 10 + int(start)
        [42, ..]:
            return 3
        _:
            return 0

func describe_dict(value) -> int:
    match value:
        {}:
            return 1
        {"name": "Dennis"}:
            return 2
        {"name": "Dennis", "age": var age}:
            return 10 + int(age)
        {"name": _, "age": _}:
            return 4
        {"key": "godotisawesome", ..}:
            return 5
        _:
            return 0

func nested_array(value) -> int:
    match value:
        [[var a, var b], var tail]:
            return int(a) * 100 + int(b) * 10 + int(tail)
        _:
            return 0

func array_of_dict(value) -> int:
    match value:
        [{"id": var id}, ..]:
            return int(id)
        _:
            return 0

func dict_of_array(value) -> int:
    match value:
        {"items": [var first, ..]}:
            return int(first)
        _:
            return 0

func empty_array() -> int:
    return describe_array([])

func specific_array() -> int:
    return describe_array([1, 3, "test", null])

func bind_then_wildcard_array() -> int:
    return describe_array([7, 9, "test"])

func open_ended_array() -> int:
    return describe_array([42, 1, 2])

func array_fallback() -> int:
    return describe_array([8])

func empty_dict() -> int:
    return describe_dict({})

func exact_name_dict() -> int:
    return describe_dict({"name": "Dennis"})

func name_and_age_dict() -> int:
    return describe_dict({"name": "Dennis", "age": 4})

func key_presence_dict() -> int:
    return describe_dict({"name": "Ada", "age": 9})

func open_ended_dict() -> int:
    return describe_dict({"key": "godotisawesome", "extra": 1})

func dict_fallback() -> int:
    return describe_dict({"other": 1})

func nested_array_hit() -> int:
    return nested_array([[1, 2], 3])

func nested_array_miss() -> int:
    return nested_array([[1], 3])

func array_of_dict_hit() -> int:
    return array_of_dict([{"id": 7}, {"id": 8}])

func dict_of_array_hit() -> int:
    return dict_of_array({"items": [9, 10]})

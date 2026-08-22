class_name MatchLambdaSmoke
extends Node

func capture_bind(value: int) -> int:
    var cb := func() -> int:
        return -1
    match value:
        var bound:
            cb = func() -> int:
                return bound
    return int(cb.call())

func match_inside_lambda(choice: int) -> int:
    var cb := func(item: int) -> int:
        match item:
            1:
                return 10
            2, 3:
                return 20
            _:
                return 1
    return int(cb.call(choice))

func capture_nested_bind(pair) -> int:
    var cb := func() -> int:
        return 0
    match pair:
        [var head, ..]:
            cb = func() -> int:
                return int(head)
        _:
            pass
    return int(cb.call())

func bind_capture_hit() -> int:
    return capture_bind(6)

func inside_lambda_one() -> int:
    return match_inside_lambda(1)

func inside_lambda_or() -> int:
    return match_inside_lambda(3)

func inside_lambda_wildcard() -> int:
    return match_inside_lambda(9)

func nested_bind_capture_hit() -> int:
    return capture_nested_bind([11, 22])

func nested_bind_capture_miss() -> int:
    return capture_nested_bind({})

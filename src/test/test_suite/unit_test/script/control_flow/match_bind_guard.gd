class_name MatchBindGuardSmoke
extends Node

func classify(value: int) -> int:
    match value:
        var bound when bound > 0:
            return bound
        _:
            return 0

func no_hit(value: int) -> int:
    match value:
        1:
            return 100
    return value

func compute() -> int:
    return classify(7) + classify(-4) + no_hit(3)

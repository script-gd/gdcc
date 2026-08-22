class_name MatchLiteralWildcardSmoke
extends Node

func classify(value: int) -> int:
    match value:
        1:
            return 10
        2, 3:
            return 20
        _:
            return 1

func compute() -> int:
    return classify(1) + classify(2) + classify(3) + classify(9)

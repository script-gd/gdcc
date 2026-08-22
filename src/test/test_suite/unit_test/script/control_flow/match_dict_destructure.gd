class_name MatchDictDestructureSmoke
extends Node

func extract(data) -> int:
    match data:
        {"kind": 1, "payload": var payload}:
            return payload
        {"kind": 2, ..}:
            return 20
        _:
            return 0

func has_user(data: Dictionary) -> int:
    match data:
        {"user": _}:
            return 1
        _:
            return 0

func nested_level(data) -> int:
    match data:
        {"user": {"level": var level}}:
            return level
        _:
            return 0

func compute() -> int:
    return extract({"kind": 1, "payload": 5}) + extract({"kind": 2, "extra": 9}) + has_user({"user": "u"}) + has_user({}) + nested_level({"user": {"level": 3}})

class_name MatchMixedSmoke
extends Node

func for_match_dict_lambda() -> int:
    var total := 0
    var rows = [
        {"skip": true, "x": 1},
        {"kind": "add", "n": 4},
        {"kind": "add", "n": 0},
        {"kind": "add", "n": 5},
        {"kind": "stop"},
        {"kind": "add", "n": 9}
    ]
    for row in rows:
        match row:
            {"skip": true, ..}:
                continue
            {"kind": "add", "n": var n} when int(n) > 0:
                var cb := func() -> int:
                    return int(n)
                total += int(cb.call())
            {"kind": "stop", ..}:
                break
            _:
                total += 1
    return total

func while_match_array_if() -> int:
    var i := 0
    var total := 0
    var items = [[1, 2], [0, 9], [3, 4], [8, 8]]
    while i < items.size():
        var item = items[i]
        i += 1
        match item:
            [var a, var b] when int(a) == 0:
                continue
            [var a, var b]:
                if int(a) == int(b):
                    break
                total += int(a) + int(b)
            _:
                pass
    return total

func string_match_in_for() -> int:
    var total := 0
    for label in ["go", "x", "run", "stop", "go"]:
        match label:
            "go", &"run":
                total += 1
            "stop":
                break
            _:
                total += 10
    return total

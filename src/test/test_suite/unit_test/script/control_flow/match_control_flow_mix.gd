class_name MatchControlFlowMixSmoke
extends Node

func match_in_for_continue_break() -> int:
    var total := 0
    for item in [0, 2, 4, 9, 8]:
        match item:
            0:
                continue
            9:
                break
            var bound:
                total += int(bound)
    return total

func match_in_while() -> int:
    var n := 0
    var total := 0
    while n < 8:
        match n:
            2:
                n += 1
                continue
            5:
                break
            _:
                total += n
        n += 1
    return total

func match_body_has_if_for(kind: int) -> int:
    match kind:
        1:
            var total := 0
            for i in range(4):
                if i % 2 == 0:
                    continue
                total += i
            return total
        2:
            if true:
                return 7
            return 0
        _:
            return -1

func nested_match(outer: int, inner: int) -> int:
    match outer:
        1:
            match inner:
                2:
                    return 12
                _:
                    return 10
        _:
            return 0

func match_in_if(flag: bool, value: int) -> int:
    if flag:
        match value:
            1:
                return 1
            _:
                return 2
    return 0

func for_continue_break() -> int:
    return match_in_for_continue_break()

func while_continue_break() -> int:
    return match_in_while()

func body_for() -> int:
    return match_body_has_if_for(1)

func body_if() -> int:
    return match_body_has_if_for(2)

func body_fallback() -> int:
    return match_body_has_if_for(0)

func nested_hit() -> int:
    return nested_match(1, 2)

func nested_inner_fallback() -> int:
    return nested_match(1, 0)

func nested_outer_fallback() -> int:
    return nested_match(0, 2)

func if_true_hit() -> int:
    return match_in_if(true, 1)

func if_true_fallback() -> int:
    return match_in_if(true, 4)

func if_false() -> int:
    return match_in_if(false, 1)

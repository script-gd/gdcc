class_name CommentStatementControlFlowSmoke
extends Node

func build_trace(limit: int) -> String:
    var index = 0
    var trace = ""
    # loop entry comment
    while index < limit:
        # branch head comment
        if index == 0:
            # then branch comment
            trace = trace + "zero"
        elif index == 1:
            # elif branch comment
            trace = trace + "|one"
        else:
            # else branch comment
            trace = trace + "|rest"
        # loop advance comment
        index = index + 1
    # loop tail comment
    return trace

func pass_then_add(seed: int) -> int:
    # comment before pass
    pass
    # comment after pass
    return seed + 2

func comment_only_return() -> String:
    # comment before return
    return "comment-ok"

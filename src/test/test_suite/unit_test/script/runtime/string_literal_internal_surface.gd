class_name StringLiteralInternalSurfaceSmoke
extends Node

func concat_internal() -> String:
    return "gdcc" + "-engine-" + "suite"

func substr_internal() -> String:
    return "abcdef".substr(2, 3)

func compare_internal() -> bool:
    var exact_match = "gdcc" == "gdcc"
    var mismatch = "gdcc" != "godot"
    var quoted_match = "\"hero\"" == "\"hero\""
    return exact_match and mismatch and quoted_match and not ("gdcc" == "godot")

func classify_internal(mode: String) -> String:
    if mode == "alpha":
        return "alpha:ok"
    elif mode == "beta":
        return "beta:ok"
    return "fallback:\"gamma\""

func escape_internal() -> String:
    return "line\nbreak\t\"quoted\""

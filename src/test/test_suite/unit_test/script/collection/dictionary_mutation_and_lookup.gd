class_name DictionaryMutationAndLookupSmoke
extends Node

func summarize() -> int:
    var scores: Dictionary = Dictionary()
    var alpha_flag := 0
    var gamma_flag := 0
    scores["alpha"] = 2
    scores["beta"] = 5
    scores["alpha"] = int(scores["alpha"]) + 4
    if scores.has("alpha"):
        alpha_flag = 1
    if scores.has("gamma"):
        gamma_flag = 1
    return scores.size() * 1000 + int(scores["alpha"]) * 100 + int(scores["beta"]) * 10 + alpha_flag * 2 + gamma_flag

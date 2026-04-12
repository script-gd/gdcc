class_name TypedDictionaryMethodPlainGuardAbiSmoke
extends Node

var accepted_calls: int

func accept_payloads(payloads: Dictionary[StringName, Node]) -> int:
    accepted_calls += 1
    return accepted_calls * 10 + payloads.size()

func read_accept_calls() -> int:
    return accepted_calls

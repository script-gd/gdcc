class_name TypedDictionaryPropertyPlainGuardAbiSmoke
extends Node

var payloads: Dictionary[StringName, Node]

func read_payload_size() -> int:
    return payloads.size()

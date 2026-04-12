class_name VariantPropertyRoundtripAbiSmoke
extends Node

var payload: Variant
@export var visible_payload: Variant
var score: int

func read_payload_size() -> int:
    return payload.size()

func read_visible_payload_size() -> int:
    return visible_payload.size()

func read_score() -> int:
    return score

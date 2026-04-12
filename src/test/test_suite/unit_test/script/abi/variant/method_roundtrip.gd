class_name VariantMethodRoundtripAbiSmoke
extends Node

var variant_calls: int

func accept_variant(value: Variant) -> int:
    variant_calls += 1
    return typeof(value) * 10 + variant_calls

func echo_variant(value: Variant) -> Variant:
    return value

func read_variant_calls() -> int:
    return variant_calls

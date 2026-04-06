class_name LocalInitializerVariantBoundaries
extends Node

func pack_and_unpack() -> int:
    var boxed: Variant = 9
    var reopened: int = boxed
    var from_call: int = _make_variant()
    return reopened + from_call

func _make_variant():
    return 33

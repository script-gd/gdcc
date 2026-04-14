class_name BuiltinVariantContainerConstructorRoundtrip
extends Node

func reopen_array(seed: Variant) -> Array:
    var local: Variant = seed
    return Array(local)

func reopen_dictionary(seed: Variant) -> Dictionary:
    var local: Variant = seed
    return Dictionary(local)

func copy_array(seed: Array) -> int:
    return Array(seed).size()

func copy_dictionary(seed: Dictionary) -> int:
    return Dictionary(seed).size()

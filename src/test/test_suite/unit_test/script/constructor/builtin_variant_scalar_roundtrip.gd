class_name BuiltinVariantScalarConstructorRoundtrip
extends Node

func reopen_int(seed: Variant) -> int:
    var local: Variant = seed
    return int(local)

func reopen_string(seed: Variant) -> String:
    var local: Variant = seed
    return String(local)

func exact_vector_sum() -> int:
    var built: Vector3i = Vector3i(1, 2, 3)
    return built.x + built.y + built.z

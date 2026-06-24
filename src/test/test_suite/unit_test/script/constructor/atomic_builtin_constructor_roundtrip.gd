class_name AtomicBuiltinConstructorRoundtrip
extends Node

func exact_int_reopen() -> int:
    var seed: int = 42
    return int(seed)

func bool_from_int_mask() -> int:
    var non_zero: int = -7
    var zero: int = 0
    var from_non_zero: bool = bool(non_zero)
    var from_zero: bool = bool(zero)
    if from_non_zero and not from_zero:
        return 3
    return -1

func int_from_bool_delta() -> int:
    var yes: bool = true
    var no: bool = false
    return int(yes) - int(no)

func float_bool_int_mix() -> float:
    var flag: bool = true
    var count: int = 5
    return float(flag) + float(count)

func int_from_float_truncation() -> int:
    var positive: float = 8.75
    var negative: float = -2.25
    return int(positive) + int(negative)

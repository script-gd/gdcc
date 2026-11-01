class_name ConstScalarConstants
extends Node

# Inferred exact types, annotation conversion, earlier-const references,
# whitelisted operators, and global/engine constants inside initializers.
const LIMIT = 20
const RATIO = 1.5
const FLAG = true
const GREETING = "gd" + "cc"
const TYPED: int = 7
const WIDENED: float = 2
const DERIVED = LIMIT * 2 + 1
const PI_VAL = PI
const OK_VAL = OK
const PRESET = Control.PRESET_CENTER

func scalar_sum() -> int:
    return LIMIT + DERIVED

func doubled_ratio() -> float:
    return RATIO * 2.0

func flag_on() -> bool:
    return FLAG

func greet() -> String:
    return GREETING + "!"

func widened() -> float:
    return WIDENED + 0.25

func typed() -> int:
    return TYPED

func globals_ok() -> bool:
    return PI_VAL > 3.14 and PI_VAL < 3.15 and OK_VAL == 0 and PRESET == 8

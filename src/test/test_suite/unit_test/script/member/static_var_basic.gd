class_name StaticVarBasic
extends Node

# No source initializer: reads must observe the materialized type default (0).
static var counter: int
# Explicit initializers. `label` exercises the destroyable lifecycle twice: the module-init
# overwrite of the materialized "" default, and a runtime overwrite via overwrite_label().
static var total: int = 10
static var label: String = "init"

static func bump(step: int) -> int:
    counter += step
    return counter

func read_counter() -> int:
    return counter

func read_total() -> int:
    return total

func write_and_read(value: int) -> int:
    total = value
    return StaticVarBasic.total

func read_label() -> String:
    return label

func overwrite_label(value: String) -> String:
    label = value
    return label

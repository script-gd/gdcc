class_name EnumExportHintSmoke
extends Node

# Script-enum bare @export surface: the validation script inspects the engine-side
# get_property_list() entries (type / hint / hint_string / usage) generated from the enum
# member tables, plus the runtime initializer value.

enum State { IDLE, JUMP = 5 }
enum Direction { MOVE_LEFT, MOVE_RIGHT }

@export var current: State = State.JUMP
@export var heading: Direction
@export_range(0, 10) var ranged: State
var untouched: State

func read_current() -> int:
    return current

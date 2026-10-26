class_name OnreadyReadyTimeInit
extends Node

# Ready-time semantics anchor: the source initializer must not run at construction,
# so `_init` observes the type default and only engine READY applies the real value.
@onready var value: int = 41

var init_value: int = -1
var ready_value: int = -1

func _init() -> void:
    init_value = value

func _ready() -> void:
    ready_value = value

func get_init_value() -> int:
    return init_value

func get_ready_value() -> int:
    return ready_value

func get_value() -> int:
    return value

class_name EnumInitializerAndDefaults
extends Node

enum State { IDLE, JUMP = 5 }
enum { WALK = 2, RUN = 4 }

var current = State.JUMP
var speed = RUN

func read_current() -> int:
	return current

func read_speed() -> int:
	return speed

func with_default(value = State.JUMP) -> int:
	return value

func with_bare_default(value = WALK) -> int:
	return value

class_name EnumMatchConstantPatterns
extends Node

enum State { IDLE, JUMP = 5 }

class Other:
	enum Mode { OFF, ON = 3 }

func describe(value: int) -> int:
	match value:
		State.IDLE:
			return 100
		State.JUMP:
			return 200
		_:
			return -1

func describe_cross(value: int) -> int:
	match value:
		Other.Mode.OFF:
			return 10
		Other.Mode.ON:
			return 30
		_:
			return -1

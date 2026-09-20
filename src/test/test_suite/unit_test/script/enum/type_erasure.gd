class_name EnumTypeErasure
extends Node

enum State { IDLE, JUMP = 5 }

func annotated_local() -> int:
	var current: State = State.JUMP
	return current

func annotated_param(value: State) -> int:
	return value + 1

func is_state_check() -> int:
	var current: State = State.IDLE
	if current is State:
		return 1
	return 0

func typed_array_sum() -> int:
	var values: Array[State] = [State.IDLE, State.JUMP]
	return values[0] + values[1]

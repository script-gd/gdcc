class_name EnumNamedMemberAccess
extends Node

enum State { IDLE, JUMP = 5, FALL }

func folded_jump() -> int:
	return State.JUMP

func arithmetic() -> int:
	return State.IDLE + State.JUMP * 2 - State.FALL

func comparison() -> int:
	if State.JUMP > State.IDLE and State.FALL == 6:
		return 1
	return 0

func count_to_jump() -> int:
	var total = 0
	for i in range(State.JUMP):
		total += i
	return total

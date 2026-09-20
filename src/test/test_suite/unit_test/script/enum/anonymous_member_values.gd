class_name EnumAnonymousMemberValues
extends Node

enum {
	IDLE,
	JUMP = 5,
	FALL,
	NEGATIVE = -2,
	MASK = 1 << 3,
	AFTER_MASK,
	COPY_OF_JUMP = JUMP,
}

func idle() -> int:
	return IDLE

func jump() -> int:
	return JUMP

func fall() -> int:
	return FALL

func negative() -> int:
	return NEGATIVE

func mask() -> int:
	return MASK

func after_mask() -> int:
	return AFTER_MASK

func copy_of_jump() -> int:
	return COPY_OF_JUMP

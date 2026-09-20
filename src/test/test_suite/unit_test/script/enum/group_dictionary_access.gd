class_name EnumGroupDictionaryAccess
extends Node

enum State { IDLE, JUMP = 5, FALL }

func subscript_jump() -> int:
	return State["JUMP"]

func key_count() -> int:
	return State.keys().size()

func first_key() -> String:
	var keys = State.keys()
	return String(keys[0])

func group_roundtrip() -> Dictionary:
	return State

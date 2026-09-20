class_name EnumCrossClassAccess
extends Node

class Base:
	enum { PARENT_IDLE = 7 }

class Other extends Base:
	enum State { IDLE, JUMP = 5 }
	enum { OWN_RUN = 3 }

func group_member() -> int:
	return Other.State.JUMP

func direct_member() -> int:
	return Other.OWN_RUN

func inherited_member() -> int:
	return Other.PARENT_IDLE

func cross_group_subscript() -> int:
	return Other.State["JUMP"]

func cross_group_key_count() -> int:
	return Other.State.keys().size()

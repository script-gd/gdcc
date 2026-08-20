class_name TernaryStatementPosition
extends Node

var marks: Array = Array()

func record(label: String) -> int:
	marks.push_back(label)
	return marks.size()

func run_discard(flag: bool) -> int:
	var before := marks.size()
	record("selected") if flag else record("skipped")
	return marks.size() - before

func last_mark() -> String:
	return marks[marks.size() - 1]

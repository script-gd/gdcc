class_name CompoundAssignmentMemberSmoke
extends Node

var total: int = 2

func run_once() -> int:
    var local: int = 3
    local += 4
    total *= local
    total -= 2
    return total

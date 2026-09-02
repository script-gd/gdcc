class_name GetNodeControlFlowSceneSmoke
extends Node

func _init():
    var child: Node = Node.new()
    child.name = StringName("Child")
    self.add_child(child)

    var other: Node = Node.new()
    other.name = StringName("Other")
    self.add_child(other)

    # Runtime-created nodes only register in the owner's unique-node map when the owner is
    # assigned before enabling the flag; without an owner the flag is a no-op.
    var unique: Node = Node.new()
    unique.name = StringName("Unique")
    self.add_child(unique)
    unique.owner = self
    unique.unique_name_in_owner = true

func if_route(tag: int) -> StringName:
    if tag == 0:
        return $Child.name
    elif tag == 1:
        return $Other.name
    return %Unique.name

func if_route_ok() -> bool:
    return if_route(0) == StringName("Child") and if_route(1) == StringName("Other") and if_route(2) == StringName("Unique")

func ternary_route(use_child: bool) -> StringName:
    var picked: Node = $Child if use_child else $Other
    return picked.name

func ternary_route_ok() -> bool:
    return ternary_route(true) == StringName("Child") and ternary_route(false) == StringName("Other")

func match_route(tag: int) -> StringName:
    match tag:
        0:
            return $Child.name
        1:
            return $Other.name
        _:
            return %Unique.name

func match_route_ok() -> bool:
    return match_route(0) == StringName("Child") and match_route(1) == StringName("Other") and match_route(9) == StringName("Unique")

func loop_hits() -> int:
    var hits := 0
    for i in range(6):
        if i == 2:
            continue
        if $Child.name != StringName("Child"):
            break
        hits += 1
    var n := 0
    while true:
        if n >= 4:
            break
        if %Unique.name != StringName("Unique"):
            break
        hits += 2
        n += 1
    return hits

func loop_hits_ok() -> bool:
    # for: 6 iterations minus the continued one = 5; while: 4 iterations * 2 = 8.
    return loop_hits() == 13

func nested_loop_hits() -> int:
    var hits := 0
    for i in range(3):
        var n := 0
        while n < 3:
            if n == 1:
                n += 1
                continue
            if $Other.name != StringName("Other"):
                break
            hits += 1
            n += 1
        if $Child.name != StringName("Child"):
            break
        if %Unique.name != StringName("Unique"):
            break
    return hits

func nested_loop_hits_ok() -> bool:
    # 3 outer iterations * 2 counted inner iterations (n == 1 is skipped by continue).
    return nested_loop_hits() == 6

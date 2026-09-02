class_name GetNodeLambdaFlowSceneSmoke
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

func lambda_if_route(choice: int) -> int:
    var cb := func(tag: int) -> int:
        if tag == 0:
            return 10 if $Child.name == StringName("Child") else -1
        elif tag == 1:
            return 20 if $Other.name == StringName("Other") else -2
        return 30 if %Unique.name == StringName("Unique") else -3
    return int(cb.call(choice))

func lambda_if_route_ok() -> bool:
    return lambda_if_route(0) == 10 and lambda_if_route(1) == 20 and lambda_if_route(9) == 30

func lambda_match_route(choice: int) -> int:
    var cb := func(tag: int) -> int:
        match tag:
            1:
                return 100 if $Child.name == StringName("Child") else -1
            2:
                return 200 if $Other.name == StringName("Other") else -2
            _:
                return 300 if %Unique.name == StringName("Unique") else -3
    return int(cb.call(choice))

func lambda_match_route_ok() -> bool:
    return lambda_match_route(1) == 100 and lambda_match_route(2) == 200 and lambda_match_route(0) == 300

func lambda_loop_hits() -> int:
    var cb := func() -> int:
        var hits := 0
        for i in range(3):
            if $Child.name != StringName("Child"):
                break
            hits += 1
        var n := 0
        while n < 2:
            if %Unique.name != StringName("Unique"):
                break
            hits += 2
            n += 1
        return hits
    return int(cb.call())

func lambda_loop_hits_ok() -> bool:
    return lambda_loop_hits() == 7

func branch_lambda_routes() -> int:
    var total := 0
    for i in range(2):
        var cb := func() -> int:
            return 1 if $Child.name == StringName("Child") else -1
        if i != 0:
            cb = func() -> int:
                return 10 if %Unique.name == StringName("Unique") else -2
        total += int(cb.call())
    return total

func branch_lambda_routes_ok() -> bool:
    return branch_lambda_routes() == 11

func loop_captured_lambdas() -> int:
    var cbs: Array = []
    for i in range(3):
        cbs.append(func() -> int:
            return i if $Child.name != StringName("Child") else i + 100
        )
    var total := 0
    for cb in cbs:
        total += int(cb.call())
    return total

func loop_captured_lambdas_ok() -> bool:
    # Each stored lambda snapshots its own iteration i and resolves $Child through the
    # implicitly captured self: (0 + 100) + (1 + 100) + (2 + 100) = 303.
    return loop_captured_lambdas() == 303

func make_nested_cb() -> Callable:
    return func() -> Callable:
        var outer_name: StringName = $Child.name
        return func() -> Array:
            return [outer_name, %Unique.name]

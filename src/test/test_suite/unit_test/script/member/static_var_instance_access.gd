class_name StaticVarInstanceAccess
extends Node

# Shared storage: writes through any instance syntax (self.x / obj.x) must be visible to
# every other access route. Instance-syntax access only produces a warning, never a block.
static var hits: int = 0

func hit_via_self() -> int:
    self.hits += 1
    return self.hits

func hit_via_other_instance() -> int:
    var other := StaticVarInstanceAccess.new()
    other.hits += 1
    return hits

func read_hits() -> int:
    return hits

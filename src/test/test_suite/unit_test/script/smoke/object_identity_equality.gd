class_name ObjectIdentityEqualitySmoke
extends Node

func identity_mask() -> int:
    var first: Node = Node.new()
    var second: Node = Node.new()
    var as_object: Object = first
    var missing: Node = null
    var parent: Node = get_parent()
    var parent_as_object: Object = parent
    var ready_object: Object = ready.get_object()
    var mask := 0

    # Low bits are the expected true cases; high bits catch false-positive identity results.
    if first == first:
        mask += 1
    if first != second:
        mask += 2
    if first == as_object:
        mask += 4
    if as_object == first:
        mask += 8
    if parent == parent_as_object:
        mask += 16
    if ready_object == self:
        mask += 32
    if missing == null:
        mask += 64

    if first == second:
        mask += 256
    if first != first:
        mask += 512
    if missing != null:
        mask += 1024
    if ready_object != self:
        mask += 2048

    first.free()
    second.free()
    return mask

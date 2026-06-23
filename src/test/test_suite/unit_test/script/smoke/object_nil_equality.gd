class_name ObjectNilEqualitySmoke
extends Node

class Point extends RefCounted:
    var next: Point = null

func equality_mask() -> int:
    var missing: Point = null
    var present: Point = Point.new()
    var mask := 0

    # Low bits are the expected true cases; high bits catch false-positive equality results.
    if missing == null:
        mask += 1
    if null == missing:
        mask += 2
    if present != null:
        mask += 4
    if null != present:
        mask += 8
    if null == null:
        mask += 16
    if present.next == null:
        mask += 32
    if null == present.next:
        mask += 64

    if present == null:
        mask += 256
    if null == present:
        mask += 512
    if missing != null:
        mask += 1024
    if null != missing:
        mask += 2048
    if null != null:
        mask += 4096

    return mask

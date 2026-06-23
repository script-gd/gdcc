class_name BenchmarkQuadtreeLookupInterpreter
extends Node

class Point extends RefCounted:
    var slot: int = -1
    var x: int = 0
    var y: int = 0
    var weight: int = 0
    var next: Point = null

class QuadNode extends RefCounted:
    var min_x: int = 0
    var min_y: int = 0
    var max_x: int = 0
    var max_y: int = 0
    var mid_x: int = 0
    var mid_y: int = 0
    var is_leaf: bool = true
    var point_count: int = 0
    var points: Point = null
    var north_west: QuadNode = null
    var north_east: QuadNode = null
    var south_west: QuadNode = null
    var south_east: QuadNode = null

var _root: QuadNode = null
var _point_count: int = 0

func prepare() -> void:
    _root = _new_node(0, 0, 64, 64)
    _point_count = 0

    _insert_point(5, 5, 3)
    _insert_point(12, 18, 5)
    _insert_point(20, 24, 7)
    _insert_point(44, 10, 11)
    _insert_point(50, 22, 13)
    _insert_point(60, 30, 17)
    _insert_point(8, 40, 19)
    _insert_point(18, 52, 23)
    _insert_point(30, 60, 29)
    _insert_point(38, 38, 31)
    _insert_point(48, 46, 37)
    _insert_point(58, 58, 41)

func baseline() -> int:
    return _point_count

func benchmark() -> int:
    var total := 0
    total += _query(_root, 0, 0, 64, 64)
    total += _query(_root, 0, 0, 32, 32) * 2
    total += _query(_root, 32, 0, 64, 32) * 3
    total += _query(_root, 0, 32, 32, 64) * 4
    total += _query(_root, 32, 32, 64, 64) * 5
    total += _query(_root, 10, 10, 50, 50) * 6
    total += _query(_root, 0, 0, 64, 16) * 7
    total += _query(_root, 0, 16, 64, 32) * 8
    total += _query(_root, 0, 32, 64, 48) * 9
    total += _query(_root, 0, 48, 64, 64) * 10
    total += _query(_root, 16, 16, 48, 48) * 11
    total += _query(_root, 32, 0, 48, 64) * 12
    total += _query(_root, 48, 0, 64, 64) * 13
    total += _query(_root, 0, 0, 16, 64) * 14
    total += _query(_root, 16, 0, 32, 64) * 15
    total += _query(_root, 24, 24, 56, 56) * 16
    total += _query(_root, 40, 8, 64, 40) * 17
    total += _query(_root, 4, 4, 24, 24) * 18
    total += _query(_root, 28, 28, 64, 64) * 19
    total += _query(_root, 12, 18, 60, 60) * 20
    return total

func check(result: int) -> bool:
    return result == 15514 and _point_count == 12 and _query(_root, 0, 0, 64, 64) == 236

func _new_point(x: int, y: int, weight: int) -> Point:
    var point: Point = Point.new()
    point.slot = _point_count
    point.x = x
    point.y = y
    point.weight = weight
    point.next = null
    return point

func _new_node(min_x: int, min_y: int, max_x: int, max_y: int) -> QuadNode:
    var node: QuadNode = QuadNode.new()
    node.min_x = min_x
    node.min_y = min_y
    node.max_x = max_x
    node.max_y = max_y
    node.mid_x = (min_x + max_x) / 2
    node.mid_y = (min_y + max_y) / 2
    node.is_leaf = true
    node.point_count = 0
    node.points = null
    return node

func _insert_point(x: int, y: int, weight: int) -> void:
    _insert(_root, _new_point(x, y, weight))
    _point_count += 1

func _insert(node: QuadNode, point: Point) -> void:
    if node.is_leaf and node.point_count < 2:
        _push_point(node, point)
        return

    if node.is_leaf:
        _subdivide(node)

    _insert(_select_child(node, point), point)

func _push_point(node: QuadNode, point: Point) -> void:
    point.next = node.points
    node.points = point
    node.point_count += 1

func _subdivide(node: QuadNode) -> void:
    node.is_leaf = false
    node.north_west = _new_node(node.min_x, node.mid_y, node.mid_x, node.max_y)
    node.north_east = _new_node(node.mid_x, node.mid_y, node.max_x, node.max_y)
    node.south_west = _new_node(node.min_x, node.min_y, node.mid_x, node.mid_y)
    node.south_east = _new_node(node.mid_x, node.min_y, node.max_x, node.mid_y)

    var current: Point = node.points
    node.points = null
    node.point_count = 0
    while current != null:
        var next: Point = current.next
        current.next = null
        _insert(_select_child(node, current), current)
        current = next

func _select_child(node: QuadNode, point: Point) -> QuadNode:
    if point.x < node.mid_x:
        if point.y < node.mid_y:
            return node.south_west
        return node.north_west

    if point.y < node.mid_y:
        return node.south_east
    return node.north_east

func _query(node: QuadNode, min_x: int, min_y: int, max_x: int, max_y: int) -> int:
    if not _intersects(node, min_x, min_y, max_x, max_y):
        return 0
    if node.is_leaf:
        return _query_points(node.points, min_x, min_y, max_x, max_y)

    var total := 0
    total += _query(node.north_west, min_x, min_y, max_x, max_y)
    total += _query(node.north_east, min_x, min_y, max_x, max_y)
    total += _query(node.south_west, min_x, min_y, max_x, max_y)
    total += _query(node.south_east, min_x, min_y, max_x, max_y)
    return total

func _query_points(point: Point, min_x: int, min_y: int, max_x: int, max_y: int) -> int:
    var total := 0
    var current: Point = point
    while current != null:
        if current.x >= min_x and current.x < max_x and current.y >= min_y and current.y < max_y:
            total += current.weight
        current = current.next
    return total

func _intersects(node: QuadNode, min_x: int, min_y: int, max_x: int, max_y: int) -> bool:
    return node.max_x > min_x and node.min_x < max_x and node.max_y > min_y and node.min_y < max_y

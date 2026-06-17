class_name BenchmarkLinkedListInterpreter
extends Node

class Cell:
    var slot: int = -1
    var value: int = 0
    var next: Cell = null

var _nil: Cell = Cell.new()
var _head: Cell = null
var _tail: Cell = null
var _free_head: Cell = null
var _size: int = 0
var _next_slot: int = 0
var _free_count: int = 0

func prepare() -> void:
    _reset_list()

func baseline() -> int:
    return _size

func benchmark() -> int:
    var first_total := _sum_values()
    _append_value(13)
    var removed := _remove_after(_head)
    _append_value(17)
    var second_total := _sum_values()
    return first_total + second_total + removed + _size

func check(result: int) -> bool:
    var passed := true
    if result != 83:
        passed = false
    elif _size != 5:
        passed = false
    elif _free_count != 0:
        passed = false
    elif _head.slot != 0 or _head.value != 3:
        passed = false
    var second: Cell = _head.next
    if second.slot == -1:
        passed = false
    elif second.slot != 2 or second.value != 7:
        passed = false
    var third: Cell = second.next
    if third.slot == -1:
        passed = false
    elif third.slot != 3 or third.value != 11:
        passed = false
    var fourth: Cell = third.next
    if fourth.slot == -1:
        passed = false
    elif fourth.slot != 4 or fourth.value != 13:
        passed = false
    elif _tail.slot != 1 or _tail.value != 17:
        passed = false
    elif fourth.next.slot != _tail.slot:
        passed = false
    elif _tail.next.slot != -1:
        passed = false
    return passed

func _reset_list() -> void:
    _nil.slot = -1
    _nil.next = _nil
    _head = _nil
    _tail = _nil
    _free_head = _nil
    _size = 0
    _next_slot = 0
    _free_count = 0
    _append_value(3)
    _append_value(5)
    _append_value(7)
    _append_value(11)

func _append_value(value: int) -> void:
    var node := _take_node(value)
    if _size == 0:
        _head = node
        _tail = node
    else:
        _tail.next = node
        _tail = node
    _size += 1

func _take_node(value: int) -> Cell:
    var node: Cell = _free_head
    if _free_count > 0:
        _free_head = node.next
        _free_count = _free_count - 1
        node.value = value
        node.next = _nil
        return node

    node = Cell.new()
    node.slot = _next_slot
    node.value = value
    node.next = _nil
    _next_slot += 1
    return node

func _remove_after(previous: Cell) -> int:
    var removed: Cell = previous.next
    if removed.slot == -1:
        return -1

    previous.next = removed.next
    if removed.slot == _tail.slot:
        _tail = previous
    var removed_slot := removed.slot
    _release_node(removed)
    _size -= 1
    return removed_slot

func _release_node(node: Cell) -> void:
    node.next = _free_head
    _free_head = node
    _free_count = _free_count + 1

func _sum_values() -> int:
    var total := 0
    var current: Cell = _head
    while current.slot != -1:
        total = total + current.value
        current = current.next
    return total

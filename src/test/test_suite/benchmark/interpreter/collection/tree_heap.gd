class_name BenchmarkTreeHeapInterpreter
extends Node

var _heap: PackedInt64Array = PackedInt64Array()

func prepare() -> void:
    _heap = PackedInt64Array()

func baseline() -> int:
    return _heap.size()

func benchmark() -> int:
    _heap_push(7)
    _heap_push(3)
    _heap_push(5)
    _heap_push(1)
    _heap_push(9)
    _heap_push(2)

    var first = _heap_pop()
    var second = _heap_pop()
    var third = _heap_pop()

    _heap_push(4)
    _heap_push(6)

    return first + second + third

func check(result: int) -> bool:
    var passed := true
    if result != 6:
        passed = false
    elif _heap.size() != 5:
        passed = false
    elif _heap[0] != 4:
        passed = false
    elif _heap[1] != 5:
        passed = false
    elif _heap[2] != 9:
        passed = false
    elif _heap[3] != 7:
        passed = false
    elif _heap[4] != 6:
        passed = false

    prepare()
    return passed

func _heap_push(value: int) -> void:
    _heap.push_back(value)
    _sift_up(_heap.size() - 1)

func _heap_pop() -> int:
    var root = _heap[0]
    var last_index := _heap.size() - 1
    var last = _heap[last_index]
    _heap.remove_at(last_index)
    if _heap.size() > 0:
        _heap[0] = last
        _sift_down(0)
    return root

func _sift_up(index: int) -> void:
    var current := index
    while current > 0:
        var parent = (current - 1) / 2
        if _heap[current] >= _heap[parent]:
            return
        _swap(current, parent)
        current = parent

func _sift_down(index: int) -> void:
    var current := index
    var size = _heap.size()
    while true:
        var left = current * 2 + 1
        var right = left + 1
        var smallest = current

        if left < size and _heap[left] < _heap[smallest]:
            smallest = left
        if right < size and _heap[right] < _heap[smallest]:
            smallest = right
        if smallest == current:
            return

        _swap(current, smallest)
        current = smallest

func _swap(left: int, right: int) -> void:
    var value = _heap[left]
    _heap[left] = _heap[right]
    _heap[right] = value

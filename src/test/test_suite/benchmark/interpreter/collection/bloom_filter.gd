class_name BenchmarkBloomFilterInterpreter
extends Node

var _bucket_count: int = 64
var _bitset: PackedInt64Array = PackedInt64Array()
var _keys: PackedInt64Array = PackedInt64Array()

func prepare() -> void:
    _bitset = PackedInt64Array()
    _bitset.resize(_bucket_count)
    _bitset.fill(0)

    _keys = PackedInt64Array()
    _keys.push_back(11)
    _keys.push_back(23)
    _keys.push_back(37)
    _keys.push_back(41)
    _keys.push_back(53)
    _keys.push_back(67)
    _keys.push_back(79)
    _keys.push_back(83)

func baseline() -> int:
    return _keys.size()

func benchmark() -> int:
    var index := 0
    while index < _keys.size():
        _insert(_keys[index])
        index += 1

    index = 0
    var hits := 0
    while index < _keys.size():
        if _contains(_keys[index]):
            hits += 1
        index += 1
    return hits

func check(result: int) -> bool:
    if result != _keys.size():
        return false

    var index := 0
    while index < _keys.size():
        if not _contains(_keys[index]):
            return false
        index += 1
    return true

func _insert(value: int) -> void:
    _set_bit(_hash_one(value))
    _set_bit(_hash_two(value))
    _set_bit(_hash_three(value))

func _contains(value: int) -> bool:
    return _test_bit(_hash_one(value)) and _test_bit(_hash_two(value)) and _test_bit(_hash_three(value))

func _set_bit(position: int) -> void:
    _bitset[position] = 1

func _test_bit(position: int) -> bool:
    return _bitset[position] == 1

func _hash_one(value: int) -> int:
    return (value * 17 + 3) % _bucket_count

func _hash_two(value: int) -> int:
    return (value * 31 + 5) % _bucket_count

func _hash_three(value: int) -> int:
    return (value * 43 + 7) % _bucket_count

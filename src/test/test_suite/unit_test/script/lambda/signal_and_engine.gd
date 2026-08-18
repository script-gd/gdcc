class_name LambdaSignalAndEngineSmoke
extends Node

signal labeled(prefix: String)
signal one_shot_labeled(prefix: String)

var last_prefix: String = ""
var one_shot_hits: int = 0

func connect_labeled() -> int:
    return labeled.connect(func(prefix: String) -> void:
        last_prefix = prefix
    )

func fire_labeled(prefix: String) -> String:
    labeled.emit(prefix)
    return last_prefix

func connect_one_shot() -> int:
    return one_shot_labeled.connect(func(prefix: String) -> void:
        one_shot_hits += 1
        last_prefix = prefix
    , Object.CONNECT_ONE_SHOT)

func fire_one_shot(prefix: String) -> int:
    one_shot_labeled.emit(prefix)
    return one_shot_hits

func map_double() -> int:
    var values: Array = [1, 2, 3]
    var mapped = values.map(func(x: int) -> int:
        return x * 2
    )
    return int(mapped[0]) * 100 + int(mapped[1]) * 10 + int(mapped[2])

func filter_even() -> int:
    var values: Array = [1, 2, 3, 4]
    var kept = values.filter(func(x: int) -> bool:
        return x % 2 == 0
    )
    return kept.size() * 10 + int(kept[0])

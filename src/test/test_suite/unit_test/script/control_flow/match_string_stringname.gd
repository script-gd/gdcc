class_name MatchStringStringNameSmoke
extends Node

func classify_text(value: String) -> int:
    match value:
        "go":
            return 1
        "stop", "halt":
            return 2
        _:
            return 0

func classify_name(value: StringName) -> int:
    match value:
        &"go":
            return 1
        &"stop", &"halt":
            return 2
        _:
            return 0

func string_hits_name_pattern(value: String) -> int:
    match value:
        &"hello":
            return 1
        _:
            return 0

func name_hits_string_pattern(value: StringName) -> int:
    match value:
        "hello":
            return 1
        _:
            return 0

func text_go() -> int:
    return classify_text("go")

func text_stop() -> int:
    return classify_text("stop")

func text_halt() -> int:
    return classify_text("halt")

func text_other() -> int:
    return classify_text("other")

func name_go() -> int:
    return classify_name(&"go")

func name_stop() -> int:
    return classify_name(&"stop")

func name_other() -> int:
    return classify_name(&"other")

func crossover_string_hit() -> int:
    return string_hits_name_pattern("hello")

func crossover_string_miss() -> int:
    return string_hits_name_pattern("no")

func crossover_name_hit() -> int:
    return name_hits_string_pattern(&"hello")

func crossover_name_miss() -> int:
    return name_hits_string_pattern(&"no")

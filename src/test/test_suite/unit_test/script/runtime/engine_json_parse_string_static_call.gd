class_name EngineJsonParseStringStaticCall
extends Node

func parse_count(text: String) -> int:
    var parsed: Variant = JSON.parse_string(text)
    if parsed is Dictionary:
        return int(parsed["n"])
    return -1

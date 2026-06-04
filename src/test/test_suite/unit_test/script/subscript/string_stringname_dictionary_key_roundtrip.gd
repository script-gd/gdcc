class_name StringStringNameDictionaryKeyRoundtrip
extends Node

func exercise_key_routes(
        named_values: Dictionary[StringName, int],
        keyed_values: Dictionary[String, int],
        named_payloads: Dictionary[StringName, PackedInt32Array],
        keyed_payloads: Dictionary[String, PackedInt32Array],
        named_seed: int,
        keyed_seed: int
) -> int:
    var key: String = "score"
    named_values[key] = 7
    var named_direct = named_values[&"score"] + named_values[key]

    var name_key: StringName = &"score"
    keyed_values[name_key] = 11
    var keyed_direct = keyed_values["score"] + keyed_values[name_key]

    var payload_key: String = "bag"
    named_payloads[payload_key] = PackedInt32Array()
    named_payloads[payload_key].push_back(named_seed)
    var named_writable = named_payloads[&"bag"][0] + named_payloads[payload_key].size() * 100

    var payload_name_key: StringName = &"bag"
    keyed_payloads[payload_name_key] = PackedInt32Array()
    keyed_payloads[payload_name_key].push_back(keyed_seed)
    var keyed_writable = keyed_payloads["bag"][0] + keyed_payloads[payload_name_key].size() * 100

    return named_direct * 1000000 + keyed_direct * 10000 + named_writable * 100 + keyed_writable

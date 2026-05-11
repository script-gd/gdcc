class_name DictionaryFloatKeyRoundtrip
extends Node

func write_and_read(box: Dictionary[float, int], key: int) -> int:
    box[key] = 7
    return box[key]

func nested_writeback(payloads: Dictionary[float, PackedInt32Array], key: int, seed: int) -> int:
    payloads[key] = PackedInt32Array()
    payloads[key].push_back(seed)
    return payloads[key][0] + payloads[key].size() * 100

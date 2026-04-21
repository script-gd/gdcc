class_name TypedDictionaryGdccInnerObjectRoundtripAbiSmoke
extends Node

class Worker extends RefCounted:
    var value: int

    func write_value(next: int) -> void:
        value = next

    func read_value() -> int:
        return value

var payloads: Dictionary[StringName, Worker]

func make_worker(seed: int) -> Worker:
    var worker: Worker = Worker.new()
    worker.write_value(seed)
    return worker

func echo_payloads(values: Dictionary[StringName, Worker]) -> Dictionary[StringName, Worker]:
    return values

func read_payload_size() -> int:
    return payloads.size()

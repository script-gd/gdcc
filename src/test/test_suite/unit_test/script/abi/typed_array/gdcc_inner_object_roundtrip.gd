class_name TypedArrayGdccInnerObjectRoundtripAbiSmoke
extends Node

class Worker extends RefCounted:
    var value: int

    func write_value(next: int) -> void:
        value = next

    func read_value() -> int:
        return value

var payloads: Array[Worker]

func make_worker(seed: int) -> Worker:
    var worker: Worker = Worker.new()
    worker.write_value(seed)
    return worker

func echo_payloads(values: Array[Worker]) -> Array[Worker]:
    return values

func read_payload_size() -> int:
    return payloads.size()

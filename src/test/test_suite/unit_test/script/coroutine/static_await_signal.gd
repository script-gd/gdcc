class_name StaticAwaitSignal
extends Node

class Emitter extends Node:
    signal pulse(value: int)

    func pulse_it(value: int) -> void:
        pulse.emit(value)

var emitter: Emitter
var result: int = -1
var done: bool = false

static func wait_for_pulse(source: Emitter) -> int:
    var value: int = await source.pulse
    return value * 3

func run() -> void:
    emitter = Emitter.new()
    result = await StaticAwaitSignal.wait_for_pulse(emitter)
    done = true

func emit_pulse(value: int) -> void:
    emitter.pulse_it(value)

func read_result() -> int:
    return result

func read_done() -> bool:
    return done

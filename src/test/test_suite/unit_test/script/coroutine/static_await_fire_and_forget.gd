class_name StaticAwaitFireAndForget
extends Node

signal release(value: int)

var events: Array = []

static func worker_a(peer: StaticAwaitFireAndForget) -> void:
    var value: int = await peer.release
    peer.events.append("a:%d" % value)

static func worker_b(peer: StaticAwaitFireAndForget) -> void:
    var value: int = await peer.release
    peer.events.append("b:%d" % value)

func start_detached() -> void:
    StaticAwaitFireAndForget.worker_a(self)
    StaticAwaitFireAndForget.worker_b(self)

func emit_release(value: int) -> void:
    release.emit(value)

func read_events() -> Array:
    return events

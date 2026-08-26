class_name StaticAwaitChain
extends Node

signal release(value: int)

var events: Array = []
var result: int = -1
var done: bool = false

static func leaf(peer: StaticAwaitChain) -> int:
    peer.events.append("leaf:wait")
    var value: int = await peer.release
    peer.events.append("leaf:done")
    return value + 1

static func middle(peer: StaticAwaitChain) -> int:
    peer.events.append("middle:wait")
    var value: int = await StaticAwaitChain.leaf(peer)
    peer.events.append("middle:done")
    return value + 2

func run() -> void:
    events.append("run:wait")
    result = await StaticAwaitChain.middle(self)
    events.append("run:done")
    done = true

func emit_release(value: int) -> void:
    release.emit(value)

func read_events() -> Array:
    return events

func read_result() -> int:
    return result

func read_done() -> bool:
    return done

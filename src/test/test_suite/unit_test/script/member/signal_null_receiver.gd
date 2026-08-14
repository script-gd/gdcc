class_name SignalNullReceiverSmoke
extends Node

signal pinged

func copy_from_null() -> Signal:
    var other: SignalNullReceiverSmoke = null
    return other.pinged

func copy_from_freed() -> Signal:
    var other := Node.new()
    other.free()
    return other.ready

class_name SignalValueReadSmoke
extends Node

signal pinged

func copy_bare() -> Signal:
    return pinged

func copy_self() -> Signal:
    return self.pinged

func copy_other(other: SignalValueReadSmoke) -> Signal:
    return other.pinged

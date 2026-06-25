class_name SingletonReceiverBindingDriftRuntime
extends Node

func frames_before_later_local() -> int:
    var frames := Engine.get_frames_drawn()
    # This local intentionally shadows the singleton after the use-site.
    var Engine := "local Engine"
    if Engine != "local Engine":
        return -1
    return frames

func frames_from_self_named_initializer() -> int:
    var Engine := Engine.get_frames_drawn()
    return Engine

class_name EngineClassIntToFloatBoundary
extends Node

func timer_wait_time_after_int_assignment() -> float:
    var timer: Timer = Timer.new()
    timer.wait_time = 2
    return timer.wait_time

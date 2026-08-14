class_name SignalInteropCompiledToInterpretedSmoke
extends Node

signal pinged
signal counted(value: int)
signal one_shot_pinged
signal deferred_pinged

func fire_pinged() -> void:
    pinged.emit()

func fire_counted(value: int) -> void:
    counted.emit(value)

func fire_one_shot() -> void:
    one_shot_pinged.emit()

func fire_deferred() -> void:
    deferred_pinged.emit()

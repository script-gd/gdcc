class_name SignalInteropEngineCrossingSmoke
extends Node

var button_hits: int = 0

func wire_button(button: Button) -> int:
    return button.pressed.connect(_on_button)

func fire_ready() -> void:
    ready.emit()

func _on_button() -> void:
    button_hits += 1

func read_button_hits() -> int:
    return button_hits

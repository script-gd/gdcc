class_name SignalConnectLambdaSmoke
extends Node

signal pinged

var lambda_hits: int = 0

func connect_lambda() -> int:
    return pinged.connect(func() -> void:
        lambda_hits += 1
    )

func fire() -> void:
    pinged.emit()

func read_hits() -> int:
    return lambda_hits

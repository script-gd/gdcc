class_name BuiltinPropertyWritebackColorSmoke
extends Node

func color_g_after_write() -> float:
    var color: Color = Color(0.1, 0.2, 0.7, 1.0)
    color.g = color.r + color.b
    return color.g

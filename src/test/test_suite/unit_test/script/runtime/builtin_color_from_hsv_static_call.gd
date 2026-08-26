class_name BuiltinColorFromHsvStaticCall
extends Node

func make_red() -> Color:
    return Color.from_hsv(0.0, 1.0, 1.0, 1.0)

func make_custom(h: float, s: float, v: float) -> Color:
    return Color.from_hsv(h, s, v, 0.5)

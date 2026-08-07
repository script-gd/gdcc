class_name CastBuiltinIdentityConversion
extends Node

func identity_int(value: int) -> int:
	return value as int

func int_to_float(value: int) -> float:
	return value as float

func float_to_int(value: float) -> int:
	return value as int

func int_to_bool_true(value: int) -> bool:
	return value as bool

func int_to_bool_false(value: int) -> bool:
	return value as bool

func string_to_int(value: String) -> int:
	return value as int

func string_to_float(value: String) -> float:
	return value as float

func as_variant(value: int) -> Variant:
	return value as Variant

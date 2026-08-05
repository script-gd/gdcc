class_name BuiltinTypeTest
extends Node

func exact_builtin_match() -> bool:
	var x := 1
	return x is int

func builtin_mismatch() -> bool:
	var x := 1.0
	return x is int

func string_match() -> bool:
	var s := "hello"
	return s is String

func float_match() -> bool:
	var f := 3.14
	return f is float

func int_is_float() -> bool:
	var x := 42
	return x is float

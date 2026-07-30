class_name VariantTypeTest
extends Node

func variant_builtin_runtime(v: Variant) -> bool:
	return v is int

func variant_object_runtime(v: Variant) -> bool:
	return v is Node

func variant_string_runtime(v: Variant) -> bool:
	return v is String

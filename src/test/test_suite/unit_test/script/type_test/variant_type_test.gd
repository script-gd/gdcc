class_name VariantTypeTest
extends Node

func variant_builtin_runtime(v: Variant) -> bool:
	return v is int

func variant_object_runtime(v: Variant) -> bool:
	return v is Node

func variant_string_runtime(v: Variant) -> bool:
	return v is String

# Phase 7: Variant is the top type — always true / is not always false.
func is_variant_int(v: int) -> bool:
	return v is Variant

func is_variant_null() -> bool:
	return null is Variant

func is_variant_node(v: Node) -> bool:
	return v is Variant

func is_variant_operand(v: Variant) -> bool:
	return v is Variant

func is_not_variant_int(v: int) -> bool:
	return v is not Variant

func is_not_variant_null() -> bool:
	return null is not Variant

func is_not_variant_node(v: Node) -> bool:
	return v is not Variant

func is_not_variant_operand(v: Variant) -> bool:
	return v is not Variant

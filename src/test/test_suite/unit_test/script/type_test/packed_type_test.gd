class_name PackedTypeTest
extends Node

func packed_match() -> bool:
	var p: PackedInt32Array = PackedInt32Array()
	return p is PackedInt32Array

func packed_mismatch() -> bool:
	var p: PackedInt32Array = PackedInt32Array()
	return p is PackedFloat32Array

func packed_string_match() -> bool:
	var p: PackedStringArray = PackedStringArray()
	return p is PackedStringArray

func packed_is_not_other() -> bool:
	var p: PackedInt32Array = PackedInt32Array()
	return p is not PackedInt64Array

func packed_is_not_bare_array() -> bool:
	var p: PackedInt32Array = PackedInt32Array()
	return p is not Array

func packed_byte_match() -> bool:
	var p: PackedByteArray = PackedByteArray()
	return p is PackedByteArray

func variant_packed_runtime(v: Variant) -> bool:
	return v is PackedInt32Array

func variant_packed_string_runtime(v: Variant) -> bool:
	return v is PackedStringArray

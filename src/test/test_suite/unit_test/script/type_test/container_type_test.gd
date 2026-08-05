class_name ContainerTypeTest
extends Node

func array_type_mask(typed_arr: Array[int], bare_arr: Array) -> int:
	var mask := 0
	if typed_arr is Array[int]:
		mask += 1
	if bare_arr is Array:
		mask += 2
	if bare_arr is Array[int]:
		mask += 4
	return mask

func dict_type_mask(typed_dict: Dictionary[String, int], bare_dict: Dictionary) -> int:
	var mask := 0
	if typed_dict is Dictionary[String, int]:
		mask += 1
	if bare_dict is Dictionary:
		mask += 2
	if bare_dict is Dictionary[String, int]:
		mask += 4
	return mask

# Bare slots that receive typed values must still match parameterized targets via runtime metadata.
func bare_slot_typed_metadata_mask(bare_arr: Array, bare_dict: Dictionary) -> int:
	var mask := 0
	if bare_arr is Array[int]:
		mask += 1
	if bare_dict is Dictionary[String, int]:
		mask += 2
	return mask

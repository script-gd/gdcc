class_name CastParameterizedContainer
extends Node

# Second dummy args uniquify C method mangling: Array/Dictionary base ABI collapses parameters.
var last_array: Array
var last_dict: Dictionary

func store_plain_as_typed(values: Array, _tag: int) -> void:
	last_array = values as Array[int]

func store_typed_as_plain(values: Array[int], _tag: String) -> void:
	last_array = values as Array

func store_typed_as_other(values: Array[int], _tag: float) -> void:
	last_array = values as Array[String]

func store_plain_dict_as_typed(values: Dictionary, _tag: int) -> void:
	last_dict = values as Dictionary[String, int]

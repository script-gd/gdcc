class_name TypedContainerLiteralBoundaries
extends Node

var typed_array_property: Array[float] = [1, 2]
var typed_dict_property: Dictionary[String, float] = {"a": 1}

func typed_array_int_to_float_local() -> int:
	# float -> int is not an ordinary boundary; cast constructor is the supported fold.
	var values: Array[float] = [1, 2, 3]
	var first = values[0]
	var last = values[2]
	return values.size() * 100 + int(first) * 10 + int(last)

func typed_dict_int_to_float_local() -> int:
	var scores: Dictionary[String, float] = {"x": 1, "y": 2}
	var x = scores["x"]
	var y = scores["y"]
	return scores.size() * 100 + int(x) * 10 + int(y)

func variant_element_unpack_to_typed() -> int:
	var raw: Variant = 5
	var values: Array[int] = [raw, 6]
	# Array[int] subscript is int — no cast.
	return values.size() * 100 + values[0] * 10 + values[1]

func object_element_lifecycle() -> int:
	var holder: Array[Node] = [Node.new()]
	var first: Node = holder[0]
	var class_name_ok := 0
	if first.get_class() == "Node":
		class_name_ok = 1
	first.free()
	return holder.size() * 10 + class_name_ok

func property_initializer_seeds() -> int:
	return typed_array_property.size() * 1000 + int(typed_array_property[1]) * 100 + typed_dict_property.size() * 10 + int(typed_dict_property["a"])

func typed_exact_call_and_return() -> int:
	return take_floats([1.0, 2.0]) * 10 + int(return_floats()[1])

func take_floats(values: Array[float]) -> int:
	return values.size() + int(values[0])

func return_floats() -> Array[float]:
	return [3.0, 4.0]

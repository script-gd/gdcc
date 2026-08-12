class_name DictionaryLiteralRoundtrip
extends Node

var typed_property: Dictionary[String, int] = {"seed": 11}

func generic_create_lookup_mutation() -> int:
	# Generic Dictionary values are Variant; mutate/read without int().
	var scores = {"alpha": 2, "beta": 5}
	scores["alpha"] = scores["alpha"] + 4
	var alpha_flag := 0
	var gamma_flag := 0
	if scores.has("alpha"):
		alpha_flag = 1
	if scores.has("gamma"):
		gamma_flag = 1
	return scores.size() * 1000 + scores["alpha"] * 100 + scores["beta"] * 10 + alpha_flag * 2 + gamma_flag

func empty_mixed_nested_and_immediate() -> int:
	var empty = {}
	var nested = {"outer": {"inner": 4}}
	var outer = nested["outer"]
	var inner = outer["inner"]
	var immediate = {"hp": 7}["hp"]
	return empty.size() * 100 + inner * 10 + immediate

func typed_local_assign_return_and_call() -> int:
	var local: Dictionary[String, int] = {"a": 1}
	local = {"b": 2, "c": 3}
	return local.size() * 100 + take_typed_dict({"x": 8}) * 10 + return_typed_dict()["y"]

func take_typed_dict(values: Dictionary[String, int]) -> int:
	return values.size() + values["x"]

func return_typed_dict() -> Dictionary[String, int]:
	return {"y": 9}

func dup_key() -> String:
	return "first"

func duplicate_key_overwrite_and_order() -> int:
	# Constant-key duplicates are sema.type_check; dynamic keys overwrite at runtime and keep first insert order.
	var scores = {dup_key(): 1, "second": 2, dup_key(): 9}
	var key_list = scores.keys()
	var first_key = String(key_list[0])
	var second_key = String(key_list[1])
	var order_code := 0
	if first_key == "first" and second_key == "second":
		order_code = 1
	return scores.size() * 1000 + scores["first"] * 100 + scores["second"] * 10 + order_code

func string_stringname_key_roundtrip() -> int:
	var named: Dictionary[StringName, int] = {&"score": 7}
	var text: Dictionary[String, int] = {"score": 11}
	# Typed Dictionary value slots are already int.
	var named_via_string = named["score"] + named[&"score"]
	var text_via_name = text[&"score"] + text["score"]
	return named_via_string * 100 + text_via_name

func typed_property_seed() -> int:
	return typed_property.size() * 100 + typed_property["seed"]

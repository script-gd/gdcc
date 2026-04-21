extends Node

const EXPECTED_RUNTIME_CLASS = "TypedDictionaryGdccInnerObjectRoundtripAbiSmoke"
const EXPECTED_INNER_CLASS = "TypedDictionaryGdccInnerObjectRoundtripAbiSmoke__sub__Worker"
const EXPECTED_INNER_CLASS_NAME = &"TypedDictionaryGdccInnerObjectRoundtripAbiSmoke__sub__Worker"

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var initial: Dictionary = target.payloads
    if initial.get_typed_key_builtin() != TYPE_STRING_NAME or initial.get_typed_value_builtin() != TYPE_OBJECT or initial.get_typed_value_class_name() != EXPECTED_INNER_CLASS_NAME or initial.get_typed_value_script() != null or not initial.is_empty():
        push_error("Typed dictionary GDCC inner object initial metadata validation failed.")
        return

    if String(target.get_class()) != EXPECTED_RUNTIME_CLASS:
        push_error("Typed dictionary GDCC inner object runtime class validation failed.")
        return

    var worker = target.call("make_worker", 9)
    var native_payloads: Dictionary[StringName, RefCounted] = {
        &"root": worker,
        &"leaf": null,
    }
    var exact = Dictionary(native_payloads, TYPE_STRING_NAME, "", null, TYPE_OBJECT, EXPECTED_INNER_CLASS, null)

    var echoed: Dictionary = target.call("echo_payloads", exact)
    if echoed.get_typed_key_builtin() != TYPE_STRING_NAME or echoed.get_typed_value_builtin() != TYPE_OBJECT or echoed.get_typed_value_class_name() != EXPECTED_INNER_CLASS_NAME or echoed.get_typed_value_script() != null or echoed.size() != 2 or echoed[&"leaf"] != null:
        push_error("Typed dictionary GDCC inner object exact-call validation failed.")
        return

    target.payloads = exact

    var payloads: Dictionary = target.payloads
    var first = payloads[&"root"]
    if payloads.get_typed_key_builtin() == TYPE_STRING_NAME and payloads.get_typed_value_builtin() == TYPE_OBJECT and payloads.get_typed_value_class_name() == EXPECTED_INNER_CLASS_NAME and payloads.get_typed_value_script() == null and payloads.size() == 2 and first != null and String(first.get_class()) == EXPECTED_INNER_CLASS and first.is_class(EXPECTED_INNER_CLASS) and first.is_class("RefCounted") and not first.is_class("Worker") and int(first.call("read_value")) == 9 and payloads[&"leaf"] == null and int(target.call("read_payload_size")) == 2:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Typed dictionary GDCC inner object property roundtrip validation failed.")

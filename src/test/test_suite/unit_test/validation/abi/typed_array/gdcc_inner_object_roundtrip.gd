extends Node

const EXPECTED_RUNTIME_CLASS = "TypedArrayGdccInnerObjectRoundtripAbiSmoke"
const EXPECTED_INNER_CLASS = "TypedArrayGdccInnerObjectRoundtripAbiSmoke__sub__Worker"
const EXPECTED_INNER_CLASS_NAME = &"TypedArrayGdccInnerObjectRoundtripAbiSmoke__sub__Worker"

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var initial: Array = target.payloads
    if initial.get_typed_builtin() != TYPE_OBJECT or initial.get_typed_class_name() != EXPECTED_INNER_CLASS_NAME or initial.get_typed_script() != null or not initial.is_empty():
        push_error("Typed array GDCC inner object initial metadata validation failed.")
        return

    if String(target.get_class()) != EXPECTED_RUNTIME_CLASS:
        push_error("Typed array GDCC inner object runtime class validation failed.")
        return

    var worker = target.call("make_worker", 7)
    var native_payloads: Array[RefCounted] = [worker, null]
    var exact = Array(native_payloads, TYPE_OBJECT, EXPECTED_INNER_CLASS, null)

    var echoed: Array = target.call("echo_payloads", exact)
    if echoed.get_typed_builtin() != TYPE_OBJECT or echoed.get_typed_class_name() != EXPECTED_INNER_CLASS_NAME or echoed.get_typed_script() != null or echoed.size() != 2 or echoed[1] != null:
        push_error("Typed array GDCC inner object exact-call validation failed.")
        return

    target.payloads = exact

    var payloads: Array = target.payloads
    var first = payloads[0]
    if payloads.get_typed_builtin() == TYPE_OBJECT and payloads.get_typed_class_name() == EXPECTED_INNER_CLASS_NAME and payloads.get_typed_script() == null and payloads.size() == 2 and first != null and String(first.get_class()) == EXPECTED_INNER_CLASS and first.is_class(EXPECTED_INNER_CLASS) and first.is_class("RefCounted") and not first.is_class("Worker") and int(first.call("read_value")) == 7 and payloads[1] == null and int(target.call("read_payload_size")) == 2:
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("Typed array GDCC inner object property roundtrip validation failed.")

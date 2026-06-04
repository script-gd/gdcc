class_name LocalInitializerStringNameToStringBoundaries
extends Node

var ready_text: String = &"property-ready"

func take_text(value: String) -> String:
    return value

func read_ready_text() -> String:
    return ready_text

func local_text() -> String:
    var value: String = &"local-alpha"
    return value

func assignment_text(value: StringName) -> String:
    var text: String = "seed"
    text = value
    return text

func property_store_text(value: StringName) -> String:
    ready_text = value
    return ready_text

func fixed_arg_text(value: StringName) -> String:
    return take_text(value)

func return_name_as_text(value: StringName) -> String:
    return value

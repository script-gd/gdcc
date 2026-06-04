class_name LocalInitializerStringToStringNameBoundaries
extends Node

var ready_name: StringName = "property-ready"

func take_name(value: StringName) -> StringName:
    return value

func read_ready_name() -> StringName:
    return ready_name

func local_name() -> StringName:
    var value: StringName = "local-alpha"
    return value

func assignment_name(value: String) -> StringName:
    var name: StringName = &"seed"
    name = value
    return name

func property_store_name(value: String) -> StringName:
    ready_name = value
    return ready_name

func fixed_arg_name(value: String) -> StringName:
    return take_name(value)

func return_text_as_name(value: String) -> StringName:
    return value

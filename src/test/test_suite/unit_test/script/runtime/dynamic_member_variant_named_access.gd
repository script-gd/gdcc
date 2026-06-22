class_name DynamicMemberVariantNamedAccessSmoke
extends Node

func read_marker(host) -> Variant:
    return host.marker

func write_marker(host, value: Variant) -> void:
    host.marker = value

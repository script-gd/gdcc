class_name DynamicMemberVariantSignalReadSmoke
extends Node

signal pinged

func read_variant_pinged(host: Variant) -> Variant:
    return host.pinged

func read_variant_ready(host: Variant) -> Variant:
    return host.ready

func read_variant_custom(host: Variant) -> Variant:
    return host.custom_pinged

class_name ExportObjectHintsSmoke
extends Node

# Bare @export Object-family metadata: Resource/Node-derived property types must publish
# PROPERTY_HINT_RESOURCE_TYPE / PROPERTY_HINT_NODE_TYPE with the property type class name in
# both hint_string and the property class_name slot (never the owner class name).

@export var texture: Texture2D
@export var target_node: Node2D

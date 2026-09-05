class_name ExportVariantHintsSmoke
extends Node

# Export-variant metadata surface: the validation script inspects the engine-side
# get_property_list() entries (type / hint / hint_string / usage) produced by these annotations.

@export var anything: Variant
@export_range(0, 20, 0.5) var speed: float
@export_range(0, 100, 1, "or_greater") var level: int
@export_enum("Warrior", "Mage") var archetype: String
@export_enum("Slow", "Fast") var pace: int
@export_flags("Fire", "Water", "Earth") var elements: int
@export_flags_2d_render var layers_2d: int
@export_file("*.png") var icon_path: String
@export_dir var folder: String
@export_global_file("*.txt") var global_path: String
@export_global_dir var global_folder: String
@export_multiline var description: String
@export_placeholder("Enter name...") var prompt: String
@export_exp_easing("attenuation", "positive_only") var easing: float
@export_color_no_alpha var tint: Color
@export_node_path("Node2D", "Sprite2D") var target_path: NodePath

var hidden: int

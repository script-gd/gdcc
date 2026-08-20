class_name TernaryObjectAncestorMerge
extends Node

func picks_flat(flag: bool) -> bool:
	var flat := Node2D.new()
	var spatial := Node3D.new()
	var chosen: Node = flat if flag else spatial
	var result := chosen == flat
	flat.free()
	spatial.free()
	return result

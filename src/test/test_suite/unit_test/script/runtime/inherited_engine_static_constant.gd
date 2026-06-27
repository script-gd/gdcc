class_name InheritedEngineStaticConstantSmoke
extends Node

# Property initializer exercising inherited engine class static load.
# NOTIFICATION_ENTER_TREE is declared on Node, accessed via Node2D (Node2D -> CanvasItem -> Node).
var enter_tree_notification: int = Node2D.NOTIFICATION_ENTER_TREE

func read_enter_tree_notification() -> int:
	# Inherited engine class constant in executable body.
	return Node2D.NOTIFICATION_ENTER_TREE

func read_enter_tree_notification_property() -> int:
	return enter_tree_notification

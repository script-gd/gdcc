extends Node

const EXPECTED_BASE = "CastGdccObject__sub__Base"
const EXPECTED_CHILD = "CastGdccObject__sub__Child"

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	var upcasted = target.call("upcast_child")
	if upcasted == null or not upcasted.is_class(EXPECTED_BASE) or not upcasted.is_class("RefCounted"):
		push_error("upcast_child expected registered Base")
		ok = false
	if int(target.call("downcast_success")) != 5:
		push_error("downcast_success expected tag 5")
		ok = false
	if target.call("downcast_null") != true:
		push_error("downcast_null expected true")
		ok = false
	if upcasted != null and not upcasted.is_class(EXPECTED_CHILD):
		# Upcasted value is still a Child instance at runtime.
		push_error("upcast_child runtime class should remain Child")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

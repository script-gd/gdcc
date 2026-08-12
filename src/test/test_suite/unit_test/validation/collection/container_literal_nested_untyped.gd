extends Node

func _ready() -> void:
	var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
	if target == null:
		push_error("Target node missing.")
		return

	var ok := true
	# matrix 3 rows; row[1]=[3,4,5]; cell=5; sum=21 -> 3000+300+50+21
	if int(target.call("nested_array_of_arrays")) != 3371:
		push_error("nested_array_of_arrays failed")
		ok = false
	# tree size 2; left size 2; value 2 -> 200+20+2
	if int(target.call("nested_dict_of_dicts")) != 222:
		push_error("nested_dict_of_dicts failed")
		ok = false
	# grid size 2; first_row size 2; bag size 2; 10+7 -> 2000+200+20+17
	if int(target.call("mutual_array_dict_nest")) != 2237:
		push_error("mutual_array_dict_nest failed")
		ok = false
	# catalog size 1; rows 2; first fields 2; cells[1]=5; second_id=2 -> 10000+2000+200+50+2
	if int(target.call("deeper_mutual_nest")) != 12252:
		push_error("deeper_mutual_nest failed")
		ok = false
	# values=[2,9,6]; total=17; first=2 -> 3000+170+2+9
	if int(target.call("generic_array_untyped_sum_mutate")) != 3181:
		push_error("generic_array_untyped_sum_mutate failed")
		ok = false
	# size 2; alpha 6; beta 5 -> 200+60+5
	if int(target.call("generic_dict_untyped_lookup_mutate")) != 265:
		push_error("generic_dict_untyped_lookup_mutate failed")
		ok = false
	# n=9, m=4
	if int(target.call("typed_boundary_from_nested_generic")) != 94:
		push_error("typed_boundary_from_nested_generic failed")
		ok = false

	if ok:
		print("__UNIT_TEST_PASS_MARKER__")

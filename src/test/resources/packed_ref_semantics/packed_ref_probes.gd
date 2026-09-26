class_name PackedRefProbes
extends RefCounted

## Dual-run comparison probe library for Packed*Array reference semantics (the gdcc-compiled
## side and the interpreter side share this same source file).
##
## Each probe_* method covers one row (or one sub-scenario) of the Packed*Array reference
## semantics matrix and prints one `PROBE|<CASE>|<payload>` line; the golden file
## `packed_ref_semantics_golden.txt` is locked by a Godot 4.5.2 interpreter run.
##
## Probe methods must be deterministic and execute in a fixed order via run_all; the golden
## line order mirrors the run_all execution order (the golden owns payload facts, while the
## case inventory — names and order — is anchored by PackedRefSemanticsGoldenInventoryTest).
## Register new scenarios in the golden file in sync.
##
## Note: this file is compiled by gdcc, so do not use syntax beyond the compiler's current
## capability surface; no preload/class_name self-references inside the library — keep the
## compilation unit self-contained.

signal array_signal(value: PackedInt32Array)
signal multi_signal(values: PackedInt32Array, tag: int, names: PackedStringArray)

## The suspended-period observations of CORO_AWAIT are written by the coroutine body and read
## after the main probe resumes (see probe_coroutine_await).
var coro_during := "unset"

class PropertyHolder extends RefCounted:
	var payloads := PackedInt32Array([1])

	func mutate_self() -> void:
		payloads.push_back(7)

	func size() -> int:
		return payloads.size()

## Fixed execution entry point for all probes. CORO_AWAIT / MIXED_COMBINATION /
## RETURN_VALUE_SHARING are coroutines (they await internally); the rest are synchronous.
## This method is itself a coroutine and calls tree.quit() itself at the end. The driver calls
## it fire-and-forget (must not await): the interpreter reports "Trying to get a return value
## of a method that returns void" when awaiting a gdcc-compiled void coroutine, so the quit
## responsibility lives in the library, not in the driver.
func run_all(tree: SceneTree) -> void:
	probe_local_alias()
	probe_parameter_visibility()
	probe_script_property()
	probe_typed_array_element()
	probe_dictionary_value()
	probe_builtin_property_mutation()
	probe_builtin_property_reassign()
	probe_builtin_property_subscript_write()
	probe_plus_equals_rebind()
	probe_duplicate()
	probe_signal_argument()
	probe_for_iteration()
	probe_append_array_alias()
	probe_resize_alias()
	probe_index_write_alias()
	probe_variant_identity()
	probe_parameter_default()
	probe_element_rebind()
	probe_string_iter_elements()
	probe_in_membership()
	probe_equality()
	probe_dict_key_hash()
	probe_as_same_family()
	await probe_coroutine_await(tree)
	probe_signal_multi()
	probe_dynamic_variant_receiver()
	probe_static_var()
	probe_lambda_capture()
	await probe_mixed_combination(tree)
	probe_control_flow_branches()
	await probe_return_value_sharing(tree)
	probe_engine_method_packed_arg()
	probe_string_array_mutation()
	tree.quit()

## Local alias sharing.
func probe_local_alias() -> void:
	var a := PackedInt32Array([1])
	var b := a
	a.push_back(7)
	var strings := PackedStringArray(["one"])
	var strings_alias := strings
	strings.push_back("seven")
	print("PROBE|LOCAL_ALIAS|int=%d,%d;string=%d,%d" % [a.size(), b.size(), strings.size(), strings_alias.size()])

## Callee mutations on a parameter are visible to the caller.
func mutate_parameter(p: PackedInt32Array) -> void:
	p.push_back(7)

func mutate_string_parameter(p: PackedStringArray) -> void:
	p.push_back("seven")

func probe_parameter_visibility() -> void:
	var r := PackedInt32Array([1])
	mutate_parameter(r)
	var strings := PackedStringArray(["one"])
	mutate_string_parameter(strings)
	print("PROBE|PARAM_VISIBILITY|int=%d;string=%d" % [r.size(), strings.size()])

## Script property mutation persists (both self-internal and external access paths).
func probe_script_property() -> void:
	var self_obj := PropertyHolder.new()
	self_obj.mutate_self()
	var self_size := self_obj.size()
	var outside_obj := PropertyHolder.new()
	outside_obj.payloads.push_back(7)
	print("PROBE|SCRIPT_PROPERTY|self=%d;outside=%d" % [self_size, outside_obj.payloads.size()])

## Typed Array element mutation persists.
func probe_typed_array_element() -> void:
	var arr: Array[PackedInt32Array] = [PackedInt32Array([1])]
	arr[0].push_back(7)
	print("PROBE|TYPED_ARRAY_ELEMENT|%d" % arr[0].size())

## Packed mutation inside a Dictionary value persists.
func probe_dictionary_value() -> void:
	var d := {"k": PackedInt32Array([1])}
	d["k"].push_back(7)
	print("PROBE|DICT_VALUE|%d" % d["k"].size())

## Builtin engine property getter returns a copy — direct mutation does not persist.
func probe_builtin_property_mutation() -> void:
	var poly := Polygon2D.new()
	poly.polygon = PackedVector2Array([Vector2.ZERO])
	poly.polygon.push_back(Vector2(1, 1))
	var size_without_reassign := poly.polygon.size()
	poly.free()
	print("PROBE|BUILTIN_PROPERTY_MUTATION|without_reassign=%d" % size_without_reassign)

## Builtin engine property explicit reassignment persists.
func probe_builtin_property_reassign() -> void:
	var poly := Polygon2D.new()
	poly.polygon = PackedVector2Array([Vector2.ZERO])
	var p := poly.polygon
	p.push_back(Vector2(2, 2))
	poly.polygon = p
	var size_with_reassign := poly.polygon.size()
	poly.free()
	print("PROBE|BUILTIN_PROPERTY_REASSIGN|with_reassign=%d" % size_with_reassign)

## Builtin engine property subscript write persists (interpreter read-modify-write) — in
## contrast to the 7a method call not persisting; this anchors the "writeback removal is
## limited to the mutating-call route" behavior.
func probe_builtin_property_subscript_write() -> void:
	var poly := Polygon2D.new()
	poly.polygon = PackedVector2Array([Vector2.ZERO, Vector2(3, 3)])
	poly.polygon[0] = Vector2(9, 9)
	var observed := poly.polygon[0]
	poly.free()
	print("PROBE|BUILTIN_PROPERTY_SUBSCRIPT_WRITE|%s" % observed)

## `a += b` produces a new array and rebinds; old aliases do not observe it.
func probe_plus_equals_rebind() -> void:
	var a := PackedInt32Array([1])
	var b := a
	a += PackedInt32Array([2])
	print("PROBE|PLUS_EQUALS_REBIND|%d,%d" % [a.size(), b.size()])

## `duplicate()` produces an independent copy.
func probe_duplicate() -> void:
	var a := PackedInt32Array([1])
	var b := a.duplicate()
	a.push_back(7)
	print("PROBE|DUPLICATE|%d,%d" % [a.size(), b.size()])

## Typed signal argument mutation is visible to the emitter.
func _on_array_signal(value: PackedInt32Array) -> void:
	value.push_back(7)

func probe_signal_argument() -> void:
	array_signal.connect(_on_array_signal)
	var source := PackedInt32Array([1])
	array_signal.emit(source)
	print("PROBE|SIGNAL_ARG|%d" % source.size())
	array_signal.disconnect(_on_array_signal)

## `for-in` live iteration — elements appended during iteration are visited by the current pass.
func probe_for_iteration() -> void:
	var a := PackedInt32Array([1, 2, 3])
	var total := 0
	for value in a:
		total += value
	var visited := 0
	var pushed := false
	for value in a:
		visited += 1
		if not pushed:
			a.push_back(9)
			pushed = true
	print("PROBE|FOR_ITER|sum=%d;size=%d;mutation_visits=%d" % [total, a.size(), visited])

## `append_array` mutation is shared through aliases.
func probe_append_array_alias() -> void:
	var a := PackedInt32Array([1])
	var b := a
	a.append_array(PackedInt32Array([2, 3]))
	print("PROBE|APPEND_ARRAY_ALIAS|%d,%d" % [a.size(), b.size()])

## `resize` mutation is shared through aliases.
func probe_resize_alias() -> void:
	var a := PackedInt32Array([1, 2, 3])
	var b := a
	a.resize(1)
	print("PROBE|RESIZE_ALIAS|%d,%d" % [a.size(), b.size()])

## Subscript writes are shared through aliases.
func probe_index_write_alias() -> void:
	var a := PackedInt32Array([1, 2])
	var b := a
	a[0] = 99
	print("PROBE|INDEX_WRITE_ALIAS|%d,%d" % [a[0], b[0]])

## Variant round-trip conversion preserves sharing (three-way sharing of a/v/b).
func probe_variant_identity() -> void:
	var a := PackedInt32Array([1])
	var v: Variant = a
	var b: PackedInt32Array = v
	a.push_back(7)
	v.push_back(8)
	print("PROBE|VARIANT_IDENTITY|%d,%d,%d" % [a.size(), b.size(), v.size()])

## Parameter default values materialize a fresh array per call.
func parameter_default(p: PackedInt32Array = PackedInt32Array([1])) -> int:
	p.push_back(7)
	return p.size()

func probe_parameter_default() -> void:
	var first := parameter_default()
	var second := parameter_default()
	print("PROBE|PARAM_DEFAULT_SHARED|%d,%d" % [first, second])

## Element slot rebinding — after `arr[0] = <new array>`, a previously extracted e still
## points at the old array.
func probe_element_rebind() -> void:
	var arr: Array[PackedInt32Array] = [PackedInt32Array([1, 2])]
	var e := arr[0]
	arr[0] = PackedInt32Array([9])
	print("PROBE|ELEMENT_REBIND|e=%d,%d;slot=%d,%d" % [e.size(), e[0], arr[0].size(), arr[0][0]])

## PackedStringArray iteration elements are String copies — mutating the loop variable does
## not affect the array.
func probe_string_iter_elements() -> void:
	var a := PackedStringArray(["one", "two"])
	var joined := ""
	for s in a:
		s = s + "!"
		joined += s
	print("PROBE|STRING_ITER_ELEMENTS|%s;%s" % [joined, ",".join(a)])

## `in` membership test matches by content.
func probe_in_membership() -> void:
	var a := PackedInt32Array([1, 2, 3])
	var int_hit := 2 in a
	var int_miss := 5 in a
	var s := PackedStringArray(["one", "two"])
	var str_hit := "one" in s
	var str_miss := "three" in s
	print("PROBE|IN_MEMBERSHIP|%d,%d,%d,%d" % [int(int_hit), int(int_miss), int(str_hit), int(str_miss)])

## `==`/`!=` compare by content (four sub-scenarios: alias, alias after mutation, same content
## different identity, unequal).
func probe_equality() -> void:
	var a := PackedInt32Array([1, 2])
	var b := a
	var eq_alias := a == b
	a.push_back(3)
	var eq_alias_after_mutation := a == b
	var same_content := PackedInt32Array([1, 2])
	var eq_same_content := same_content == PackedInt32Array([1, 2])
	var neq_different := a != same_content
	print("PROBE|EQUALITY|%d,%d,%d,%d" % [int(eq_alias), int(eq_alias_after_mutation), int(eq_same_content), int(neq_different)])

## Dictionary keys hash by content: mutating an inserted key through a shared alias makes the
## old entry lookup fail, and re-inserting the mutated key creates a second entry.
func probe_dict_key_hash() -> void:
	var k := PackedInt32Array([1, 2])
	var d := {k: "v"}
	var lookup_same_content: Variant = d.get(PackedInt32Array([1, 2]), "missing")
	var alias := k
	k.push_back(3)
	var lookup_mutated_key: Variant = d.get(alias, "missing")
	var lookup_mutated_fresh: Variant = d.get(PackedInt32Array([1, 2, 3]), "missing")
	var after_mutation_size := d.size()
	d[alias] = "w"
	print("PROBE|DICT_KEY_HASH|same_content=%s;mutated=%s;fresh=%s;size_after_mutate=%d;size_after_reinsert=%d" % [
		str(lookup_same_content), str(lookup_mutated_key), str(lookup_mutated_fresh), after_mutation_size, d.size()
	])

## Same-family `as` produces a COW copy (fresh identity), NOT sharing — locked by interpreter
## probing. After a.push_back(7): a=2, b=1, v=2.
## Additional probe: a static same-type `as` (c) is likewise a COW copy — after the second
## push: a=3, v=3 (shared), b=1, c=2 (the two `as` results are independent).
func probe_as_same_family() -> void:
	var a := PackedInt32Array([1])
	var v: Variant = a
	var b := v as PackedInt32Array
	a.push_back(7)
	var c := a as PackedInt32Array
	a.push_back(8)
	print("PROBE|AS_SAME_FAMILY|%d,%d,%d,%d" % [a.size(), b.size(), v.size(), c.size()])

## Coroutine parameter/capture mutations are visible in both directions across await
## suspension. The resume order relies on process_frame dispatching in connection order: the
## coroutine body resumes first (records during and pushes 4/44), the main probe resumes
## afterwards and prints.
func _coro_mutator(param: PackedInt32Array, captured: PackedInt32Array, tree: SceneTree) -> void:
	param.push_back(2)
	await tree.process_frame
	coro_during = "%d,%d" % [param.size(), captured.size()]
	param.push_back(4)
	captured.push_back(44)

func probe_coroutine_await(tree: SceneTree) -> void:
	coro_during = "unset"
	var param := PackedInt32Array([1])
	var captured := PackedInt32Array([10])
	_coro_mutator(param, captured, tree)
	var before := "%d,%d" % [param.size(), captured.size()]
	param.push_back(3)
	captured.push_back(33)
	await tree.process_frame
	print("PROBE|CORO_AWAIT|before=%s;during=%s;after=%d,%d" % [before, coro_during, param.size(), captured.size()])

## Multi-argument typed signal carrying packed: callback mutation is visible to the emitter.
func _on_multi_signal(values: PackedInt32Array, tag: int, names: PackedStringArray) -> void:
	values.push_back(tag)
	names.push_back("seven")

func probe_signal_multi() -> void:
	multi_signal.connect(_on_multi_signal)
	var values := PackedInt32Array([1])
	var names := PackedStringArray(["one"])
	multi_signal.emit(values, 5, names)
	print("PROBE|SIGNAL_MULTI|%d,%d,%d" % [values.size(), values[1], names.size()])
	multi_signal.disconnect(_on_multi_signal)

## Dynamic Variant receiver route case: a dynamic mutating call on a Variant variable is
## visible to the original variable through the shared identity.
func probe_dynamic_variant_receiver() -> void:
	var a := PackedInt32Array([1])
	var v: Variant = a
	v.push_back(7)
	print("PROBE|DYNAMIC_VARIANT_MUTATION|%d,%d" % [a.size(), v.size()])

## Static variable mutation persists (the static leaf shares identity under Variant storage).
## Note the explicit type annotation is required: `static var x := ...` performs no type
## inference (metadata falls back to Variant), and mutating calls on a Variant static carrier
## remain a pre-existing gdcc fail-closed surface — not this case's target.
static var static_packed: PackedInt32Array = PackedInt32Array([1])

func mutate_static() -> void:
	static_packed.push_back(7)

func read_static_size() -> int:
	return static_packed.size()

func probe_static_var() -> void:
	mutate_static()
	print("PROBE|STATIC_VAR|%d" % read_static_size())

## Lambda capture mutations are visible in both directions (the capture slot is a Variant
## sharing identity).
func probe_lambda_capture() -> void:
	var a := PackedInt32Array([1])
	var callback := func() -> void: a.push_back(7)
	callback.call()
	print("PROBE|LAMBDA_CAPTURE|%d" % a.size())

## Mixed scenario: script property + lambda capture + signal + coroutine await + for live
## iteration combined in one deterministic chain observing each other. Flow: the coroutine body
## pushes 2 then suspends; the main probe live-iterates the property array, and the iteration
## body emits a signal that grows the array (the new element is visited by the current pass);
## after the main probe awaits, the coroutine resumes first, calls the captured lambda to grow
## local and pushes the property again; the main probe resumes last and prints all
## observations. Property / capture / signal argument / iteration source are all visible to
## each other through the shared identity.
var mix_property := PackedInt32Array([1])
var mix_lambda: Callable

func _on_mix_signal(value: PackedInt32Array) -> void:
	value.push_back(9)

func _mix_coroutine(local: PackedInt32Array, tree: SceneTree) -> void:
	mix_property.push_back(2)
	await tree.process_frame
	mix_lambda.call()
	mix_property.push_back(4)

func probe_mixed_combination(tree: SceneTree) -> void:
	mix_property = PackedInt32Array([1])
	var local := PackedInt32Array([10])
	mix_lambda = func() -> void: local.push_back(20)
	array_signal.connect(_on_mix_signal)
	_mix_coroutine(local, tree)
	var sum := 0
	var visits := 0
	for v in mix_property:
		sum += v
		visits += 1
		if visits == 1:
			array_signal.emit(mix_property)
	array_signal.disconnect(_on_mix_signal)
	await tree.process_frame
	print("PROBE|MIXED_COMBINATION|sum=%d;visits=%d;property=%d;local=%d" % [
		sum, visits, mix_property.size(), local.size()])

## Complex control flow: nested if/elif/else and match (literal / combined branches / guard /
## wildcard / nested match) with direct packed mutation inside branch bodies; branch selection
## and in-branch mutation are visible to aliases through the shared identity, and the final
## content depends on the actually hit branches.
func probe_control_flow_branches() -> void:
	var a := PackedInt32Array([1])
	var alias := a
	if a[0] < 0:
		a.push_back(-1)
	elif a[0] == 1:
		a.push_back(2)
		if alias.size() == 2:
			a.push_back(3)
		else:
			a.push_back(-3)
	else:
		a.push_back(99)
	match a.size():
		1:
			a.push_back(10)
		2, 3:
			a.push_back(20)
			match a[2]:
				var bound when bound > 0:
					a.push_back(bound * 10)
				_:
					a.push_back(-30)
		_:
			a.push_back(40)
	var tag := 0
	if alias.size() == 5:
		match a[3]:
			20:
				tag = 1
			_:
				tag = -1
	match a[0]:
		2:
			a.push_back(70)
		_:
			a.push_back(60)
	print("PROBE|CONTROL_FLOW_BRANCHES|%s;%d,%d" % [str(a), alias.size(), tag])

## Return-value identity contract: returning a locally built array (no second holder; usable
## and mutable), returning after mutating a parameter (the original array and the returned
## alias share), multi-branch returns (field returns share / freshly built returns are
## independent), returning through a lambda capture (capture slot shares), and returning after
## a coroutine await (post-resume mutations are visible on both the returned alias and the
## field).
var return_field := PackedInt32Array([100])

func _ret_build_local() -> PackedInt32Array:
	var local := PackedInt32Array([1])
	local.push_back(2)
	return local

func _ret_mutate_param(a: PackedInt32Array) -> PackedInt32Array:
	a.push_back(7)
	return a

func _ret_branch(flag: bool) -> PackedInt32Array:
	if flag:
		return return_field
	return PackedInt32Array([9])

func _ret_after_await(tree: SceneTree) -> PackedInt32Array:
	return_field.push_back(101)
	await tree.process_frame
	return_field.push_back(102)
	return return_field

func probe_return_value_sharing(tree: SceneTree) -> void:
	var built := _ret_build_local()
	built.push_back(3)
	var src := PackedInt32Array([1])
	var out := _ret_mutate_param(src)
	out.push_back(8)
	return_field = PackedInt32Array([100])
	var via_branch := _ret_branch(true)
	var fresh := _ret_branch(false)
	fresh.push_back(10)
	var captured := PackedInt32Array([50])
	var getter := func() -> PackedInt32Array: return captured
	var via_lambda: PackedInt32Array = getter.call()
	via_lambda.push_back(51)
	var via_coro: PackedInt32Array = await _ret_after_await(tree)
	via_coro.push_back(103)
	print("PROBE|RETURN_VALUE_SHARING|built=%d;src=%d,%d;branch=%d;fresh=%d;lambda=%d,%d;coro=%d,%d" % [
		built.size(), src.size(), src[2], via_branch.size(), fresh.size(),
		captured.size(), captured[1], via_coro.size(), return_field.size()])

## Engine method boundary: in-place mutation after a builtin method returns packed
## (String.split, wrap_temp path) is visible through an alias; a builtin method receiving a
## packed argument (String.join, internal_ptr path) reads the post-mutation content on the
## alias; the instance engine methods StreamPeerBuffer.set_data_array/get_data_array take
## packed arguments and return packed values (engine copy semantics on both sides: local
## mutation after set does not flow back into the first get; get returns an independent new
## array — a second get after mutation still yields the engine-internal state).
func probe_engine_method_packed_arg() -> void:
	var parts := "a,b".split(",")
	var parts_alias := parts
	parts.push_back("c")
	var joined := "/".join(parts_alias)
	var peer: StreamPeerBuffer = StreamPeerBuffer.new()
	var bytes := PackedByteArray([1, 2, 3])
	peer.set_data_array(bytes)
	bytes.push_back(4)
	var read_back := peer.get_data_array()
	read_back.push_back(9)
	var reread := peer.get_data_array()
	print("PROBE|ENGINE_METHOD_PACKED_ARG|%s;%d,%d;%s;%s" % [
		joined, bytes.size(), read_back.size(), str(read_back), str(reread)])

## PackedStringArray specifics: aliased push_back / subscript write / insert / remove_at are
## visible through the shared identity; content ==/!= (two sub-scenarios: same content
## different identity, and after shared-alias mutation); content confirmation after
## sort/reverse applied through the shared identity.
func probe_string_array_mutation() -> void:
	var a := PackedStringArray(["one"])
	var alias := a
	a.push_back("two")
	alias[0] = "ONE"
	a.insert(1, "mid")
	alias.remove_at(2)
	var b := PackedStringArray(["ONE", "mid"])
	var eq_same := a == b
	var c := a
	c.push_back("x")
	var eq_after := a == b
	var neq_after := a != b
	var d := PackedStringArray(["b", "a", "c"])
	var d_alias := d
	d.sort()
	d_alias.reverse()
	print("PROBE|STRING_ARRAY_MUTATION|%s;%d,%d,%d;%s" % [
		",".join(a), int(eq_same), int(eq_after), int(neq_after), ",".join(d)])

class_name PackedRefProbes
extends RefCounted

## Packed*Array 引用语义双跑对照探针库（gdcc 编译侧与解释器侧共用同一源文件）。
##
## 每个 probe_* 方法对应 packed_array_reference_semantics_plan.md §2 语义矩阵的一行
## （或一行的子场景），打印一行 `PROBE|<CASE>|<payload>`；golden 文件
## `packed_ref_semantics_golden.txt` 由 Godot 4.5.2 解释器运行锁定。
##
## 探针方法必须是确定性的、按固定顺序经 run_all 执行；golden 文件的行序与 run_all
## 执行顺序一一对应（golden 承载 payload 事实，§2 矩阵用例清单由
## PackedRefSemanticsGoldenInventoryTest 锚定），新增场景时在 golden 文件中同步登记。
##
## 注意：供 gdcc 编译的源文件不要使用超出当前编译器能力面的语法；库内禁止
## preload/class_name 自引用，保持编译单元自包含。

signal array_signal(value: PackedInt32Array)
signal multi_signal(values: PackedInt32Array, tag: int, names: PackedStringArray)

## CORO_AWAIT 的挂起期观测值由协程体写入，主探针恢复后读取（见 probe_coroutine_await）。
var coro_during := "unset"

class PropertyHolder extends RefCounted:
	var payloads := PackedInt32Array([1])

	func mutate_self() -> void:
		payloads.push_back(7)

	func size() -> int:
		return payloads.size()

## 全部探针的固定执行入口。CORO_AWAIT / MIXED_COMBINATION / RETURN_VALUE_SHARING
## 为协程（内部 await），其余为同步方法；本方法是协程（内部 await），
## 执行到末尾时自行调用 tree.quit()。driver 以 fire-and-forget 方式调用（不得 await）：
## 解释器 await gdcc 编译的 void 协程会报 "Trying to get a return value of a method that
## returns void"，因此 quit 责任在库内而不在 driver。
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

## §2-1：局部别名共享。
func probe_local_alias() -> void:
	var a := PackedInt32Array([1])
	var b := a
	a.push_back(7)
	var strings := PackedStringArray(["one"])
	var strings_alias := strings
	strings.push_back("seven")
	print("PROBE|LOCAL_ALIAS|int=%d,%d;string=%d,%d" % [a.size(), b.size(), strings.size(), strings_alias.size()])

## §2-2：callee 对形参的 mutation 对调用方可见。
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

## §2-3：脚本属性 mutation 持久（self 内部与外部访问两条路径）。
func probe_script_property() -> void:
	var self_obj := PropertyHolder.new()
	self_obj.mutate_self()
	var self_size := self_obj.size()
	var outside_obj := PropertyHolder.new()
	outside_obj.payloads.push_back(7)
	print("PROBE|SCRIPT_PROPERTY|self=%d;outside=%d" % [self_size, outside_obj.payloads.size()])

## §2-5：typed Array 元素 mutation 持久。
func probe_typed_array_element() -> void:
	var arr: Array[PackedInt32Array] = [PackedInt32Array([1])]
	arr[0].push_back(7)
	print("PROBE|TYPED_ARRAY_ELEMENT|%d" % arr[0].size())

## §2-6：Dictionary 值中的 packed mutation 持久。
func probe_dictionary_value() -> void:
	var d := {"k": PackedInt32Array([1])}
	d["k"].push_back(7)
	print("PROBE|DICT_VALUE|%d" % d["k"].size())

## §2-7a：内建引擎属性 getter 返回副本——直接 mutation 不持久。
func probe_builtin_property_mutation() -> void:
	var poly := Polygon2D.new()
	poly.polygon = PackedVector2Array([Vector2.ZERO])
	poly.polygon.push_back(Vector2(1, 1))
	var size_without_reassign := poly.polygon.size()
	poly.free()
	print("PROBE|BUILTIN_PROPERTY_MUTATION|without_reassign=%d" % size_without_reassign)

## §2-7b：内建引擎属性显式重赋值持久。
func probe_builtin_property_reassign() -> void:
	var poly := Polygon2D.new()
	poly.polygon = PackedVector2Array([Vector2.ZERO])
	var p := poly.polygon
	p.push_back(Vector2(2, 2))
	poly.polygon = p
	var size_with_reassign := poly.polygon.size()
	poly.free()
	print("PROBE|BUILTIN_PROPERTY_REASSIGN|with_reassign=%d" % size_with_reassign)

## §2-7c：内建引擎属性的索引写持久（解释器 read-modify-write）——与 7a 方法调用
## 不持久相对，是"写回移除仅限定 mutating-call route"修订的行为锚点。
func probe_builtin_property_subscript_write() -> void:
	var poly := Polygon2D.new()
	poly.polygon = PackedVector2Array([Vector2.ZERO, Vector2(3, 3)])
	poly.polygon[0] = Vector2(9, 9)
	var observed := poly.polygon[0]
	poly.free()
	print("PROBE|BUILTIN_PROPERTY_SUBSCRIPT_WRITE|%s" % observed)

## §2-8：`a += b` 产生新数组并重绑定，旧别名不可见。
func probe_plus_equals_rebind() -> void:
	var a := PackedInt32Array([1])
	var b := a
	a += PackedInt32Array([2])
	print("PROBE|PLUS_EQUALS_REBIND|%d,%d" % [a.size(), b.size()])

## §2-9：`duplicate()` 产生独立副本。
func probe_duplicate() -> void:
	var a := PackedInt32Array([1])
	var b := a.duplicate()
	a.push_back(7)
	print("PROBE|DUPLICATE|%d,%d" % [a.size(), b.size()])

## §2-10：typed 信号参数 mutation 对发射方可见。
func _on_array_signal(value: PackedInt32Array) -> void:
	value.push_back(7)

func probe_signal_argument() -> void:
	array_signal.connect(_on_array_signal)
	var source := PackedInt32Array([1])
	array_signal.emit(source)
	print("PROBE|SIGNAL_ARG|%d" % source.size())
	array_signal.disconnect(_on_array_signal)

## §2-12：`for-in` 活迭代——迭代期间追加的元素会被本轮访问。
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

## §2-13：`append_array` mutation 经别名共享。
func probe_append_array_alias() -> void:
	var a := PackedInt32Array([1])
	var b := a
	a.append_array(PackedInt32Array([2, 3]))
	print("PROBE|APPEND_ARRAY_ALIAS|%d,%d" % [a.size(), b.size()])

## §2-14：`resize` mutation 经别名共享。
func probe_resize_alias() -> void:
	var a := PackedInt32Array([1, 2, 3])
	var b := a
	a.resize(1)
	print("PROBE|RESIZE_ALIAS|%d,%d" % [a.size(), b.size()])

## §2-15：索引写经别名共享。
func probe_index_write_alias() -> void:
	var a := PackedInt32Array([1, 2])
	var b := a
	a[0] = 99
	print("PROBE|INDEX_WRITE_ALIAS|%d,%d" % [a[0], b[0]])

## §2-16：Variant 双向转换保持共享（a/v/b 三方共享）。
func probe_variant_identity() -> void:
	var a := PackedInt32Array([1])
	var v: Variant = a
	var b: PackedInt32Array = v
	a.push_back(7)
	v.push_back(8)
	print("PROBE|VARIANT_IDENTITY|%d,%d,%d" % [a.size(), b.size(), v.size()])

## §2-17：参数默认值每次调用物化新数组。
func parameter_default(p: PackedInt32Array = PackedInt32Array([1])) -> int:
	p.push_back(7)
	return p.size()

func probe_parameter_default() -> void:
	var first := parameter_default()
	var second := parameter_default()
	print("PROBE|PARAM_DEFAULT_SHARED|%d,%d" % [first, second])

## §2-18：元素槽重绑定——`arr[0] = 新数组` 后先前取出的 e 仍指向旧数组。
func probe_element_rebind() -> void:
	var arr: Array[PackedInt32Array] = [PackedInt32Array([1, 2])]
	var e := arr[0]
	arr[0] = PackedInt32Array([9])
	print("PROBE|ELEMENT_REBIND|e=%d,%d;slot=%d,%d" % [e.size(), e[0], arr[0].size(), arr[0][0]])

## §2-19：PackedStringArray 迭代元素为 String 副本——改循环变量不影响数组。
func probe_string_iter_elements() -> void:
	var a := PackedStringArray(["one", "two"])
	var joined := ""
	for s in a:
		s = s + "!"
		joined += s
	print("PROBE|STRING_ITER_ELEMENTS|%s;%s" % [joined, ",".join(a)])

## §2-20：`in` 成员测试按内容匹配。
func probe_in_membership() -> void:
	var a := PackedInt32Array([1, 2, 3])
	var int_hit := 2 in a
	var int_miss := 5 in a
	var s := PackedStringArray(["one", "two"])
	var str_hit := "one" in s
	var str_miss := "three" in s
	print("PROBE|IN_MEMBERSHIP|%d,%d,%d,%d" % [int(int_hit), int(int_miss), int(str_hit), int(str_miss)])

## §2-21：`==`/`!=` 内容相等（别名、mutation 后别名、同内容异身份、不等四个子场景）。
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

## §2-21 补充：Dictionary 键按内容哈希；键插入后经共享别名 mutation 使旧条目
## 查找失败，以 mutation 后的键再插入产生第二个条目。
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

## §2-22：同 family `as` 产生 COW 拷贝（新身份）而非共享——探针实测锁定，
## 修正计划 §2 第 22 行原始预期。a.push_back(7) 后 a=2、b=1、v=2。
## 补充探针：静态同型 `as`（c）同样是 COW 拷贝——第二次 push 后
## a=3、v=3（共享）、b=1、c=2（两个 `as` 结果各自独立）。
func probe_as_same_family() -> void:
	var a := PackedInt32Array([1])
	var v: Variant = a
	var b := v as PackedInt32Array
	a.push_back(7)
	var c := a as PackedInt32Array
	a.push_back(8)
	print("PROBE|AS_SAME_FAMILY|%d,%d,%d,%d" % [a.size(), b.size(), v.size(), c.size()])

## §2-23：协程形参/捕获在 await 挂起前后双向可见。恢复顺序依赖 process_frame
## 按连接先后分发：协程体先恢复（记录 during 并 push 4/44），主探针后恢复打印。
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

## §2-24：多参数 typed 信号携带 packed，回调 mutation 对发射方可见。
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

## 计划 §5 动态 Variant receiver route 用例：Variant 变量上的动态
## mutating 调用经共享身份对原变量可见。
func probe_dynamic_variant_receiver() -> void:
	var a := PackedInt32Array([1])
	var v: Variant = a
	v.push_back(7)
	print("PROBE|DYNAMIC_VARIANT_MUTATION|%d,%d" % [a.size(), v.size()])

## §2-4：静态变量 mutation 持久（Variant 存储下静态 leaf 共享身份）。
## 注意必须显式类型标注：`static var x := ...` 不做类型推导（metadata 落 Variant），
## Variant 静态载体的 mutating 调用仍是 gdcc 既有 fail-closed 面，不属于本用例目标。
static var static_packed: PackedInt32Array = PackedInt32Array([1])

func mutate_static() -> void:
	static_packed.push_back(7)

func read_static_size() -> int:
	return static_packed.size()

func probe_static_var() -> void:
	mutate_static()
	print("PROBE|STATIC_VAR|%d" % read_static_size())

## §2-11：lambda 捕获后 mutation 双向可见（捕获槽 Variant 共享身份）。
func probe_lambda_capture() -> void:
	var a := PackedInt32Array([1])
	var callback := func() -> void: a.push_back(7)
	callback.call()
	print("PROBE|LAMBDA_CAPTURE|%d" % a.size())

## 混合场景（计划 §6 Phase F 全量验收）：脚本属性 + lambda 捕获 + 信号 + 协程 await +
## for 活迭代组合在同一条确定性链路中互相观测。流程：协程体先 push 2 后挂起；主探针对
## 属性数组做 for 活迭代，迭代体发射信号使数组增长（新元素被本轮访问）；主探针 await
## 后协程恢复（先恢复方），调用捕获 lambda 增长 local 并再 push 属性；主探针后恢复打印
## 全部观测值。属性 / 捕获 / 信号参数 / 迭代源全部经共享身份互相可见。
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

## 复杂控制流（Phase F 补充）：if/elif/else 嵌套与 match（字面值 / 合并分支 / guard /
## 通配 / 嵌套 match）的分支体内直接 mutation packed，分支选择与分支内 mutation 经
## 共享身份对别名可见，最终内容取决于实际命中分支。
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

## 返回值身份合同（Phase F 补充）：局部构建返回（无第二持有者，可用可 mutation）、
## 参数 mutation 后返回（原数组与返回别名共享）、多分支返回（字段返回共享 / 新建返回
## 独立）、lambda 捕获返回（捕获槽共享）、协程 await 后返回（恢复后 mutation 与返回
## 别名、字段三方共享）。
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

## 引擎方法边界（Phase F 补充）：builtin 方法返回 packed（String.split，wrap_temp 路径）
## 后原位 mutation 经别名可见；builtin 方法接收 packed 参数（String.join，internal_ptr
## 路径）读出别名上 mutation 后内容；实例引擎方法
## StreamPeerBuffer.set_data_array/get_data_array 的 packed 参数与返回（引擎拷贝语义
## 两侧一致：set 后本地 mutation 不回流进首次 get；get 返回独立新数组——mutation 后
## 再次 get 仍为引擎内部状态）。
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

## PackedStringArray 专项（Phase F 补充）：别名 push_back / 索引写 / insert / remove_at
## 经共享身份可见；内容 ==/!=（身份不同内容相同、共享别名 mutation 后两子场景）；
## sort/reverse 作用于共享身份后的内容确认。
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

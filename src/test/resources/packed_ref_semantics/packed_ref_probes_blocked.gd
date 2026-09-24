class_name PackedRefProbesBlocked
extends RefCounted

## 编译受阻伴随探针库：现行 gdcc 在**编译期 fail-closed**（而非运行时分歧）的用例集合。
## 单个用例的编译失败会拖垮整个模块，因此它们不能与主探针库同一模块编译；harness 对本库
## 单独尝试编译，失败即记录为 compile-blocked，意外成功则在 transcript 中报告迁移信号。
##
## 当前成员：
## - STATIC_VAR（§2-4）：`static_packed.push_back(7)` 的可写 route 以 STATIC_CONTEXT 为根、
##   静态属性 leaf 自身即终态，但 packed 属值语义写回 family，
##   `FrontendCfgGraphBuilder.appendCallReceiverCommitSteps` 的静态分支仍追加 promotion
##   step，被 `FrontendCfgGraph` 的 static-terminal 合同拒绝（有意 fail-fast）。预计由
##   Phase C（Variant 存储使静态 leaf 共享身份）+ Phase D（family 谓词改造）共同解锁。
## - LAMBDA_CAPTURE（§2-11）：对 CAPTURE binding 的 mutating 调用在 direct-slot alias 发布
##   处 fail-closed（"before lambda/capture semantics are implemented"，与 family 无关）。
##   Variant 模型下捕获即共享身份、无需 alias 发布，预计由 Phase D 的 route 改造解锁。
##
## 计划 §5/§8 原先假设这两个 route "现行可编译、运行时分歧"，与实际的 fail-closed 不符；
## 两用例登记 Phase D。

static var static_packed := PackedInt32Array([1])

func run_all() -> void:
	probe_static_var()
	probe_lambda_capture()

## §2-4：静态变量 mutation 持久。
func mutate_static() -> void:
	static_packed.push_back(7)

func read_static_size() -> int:
	return static_packed.size()

func probe_static_var() -> void:
	mutate_static()
	print("PROBE|STATIC_VAR|%d" % read_static_size())

## §2-11：lambda 捕获后 mutation 双向可见。
func probe_lambda_capture() -> void:
	var a := PackedInt32Array([1])
	var callback := func() -> void: a.push_back(7)
	callback.call()
	print("PROBE|LAMBDA_CAPTURE|%d" % a.size())

class_name PackedCallFuncIdentity
extends Node

## Packed*Array 引用语义 call_func 边界锚点（packed_array_reference_semantics_plan.md
## §2 第 2/16 行 + §4.3.12）：GDScript 解释器经普通方法调用（call_func Variant ABI）把
## packed 数组交给编译类，callee 的 mutation 必须与调用方共享身份；反向（编译类持有数组、
## 解释器后续观测）同样共享。ptrcall ABI 例外不在这条路径上（见
## PackedRefStorageModelSmokeTest 的运行锚定）。

var retained: PackedInt32Array

func mutate_and_count(a: PackedInt32Array) -> int:
    a.push_back(7)
    return a.size()

func append_and_count(a: PackedInt32Array, extra: PackedInt32Array) -> int:
    a.append_array(extra)
    return a.size()

func mutate_variant(a: Variant) -> int:
    a.push_back(9)
    return a.size()

func retain_and_touch(a: PackedInt32Array) -> void:
    retained = a
    retained.push_back(5)

func touch_retained() -> void:
    retained.push_back(6)

func produce_retained() -> PackedInt32Array:
    return retained

func read_retained_size() -> int:
    return retained.size()

func produce() -> PackedInt32Array:
    return PackedInt32Array([1, 2])
